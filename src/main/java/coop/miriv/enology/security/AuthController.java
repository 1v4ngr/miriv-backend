package coop.miriv.enology.security;

import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.security.dto.LoginRequest;
import coop.miriv.enology.security.dto.LoginResponse;
import coop.miriv.enology.security.dto.PasswordResetRequest;
import coop.miriv.enology.security.dto.PasswordResetResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService attemptService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          LoginAttemptService attemptService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.attemptService = attemptService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (attemptService.isLocked(request.username())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Cuenta temporalmente bloqueada por demasiados intentos fallidos. Vuelve a intentarlo en 15 minutos.");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            attemptService.recordSuccess(request.username());
            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            var authorities = authentication.getAuthorities().stream().map(Object::toString).toList();
            String token = jwtService.issueAccessToken(principal.getUsername(), authorities);
            return new LoginResponse(token, jwtService.accessTokenValiditySeconds(), principal.getUserId(),
                principal.getUser().getFullName(), authorities);
        } catch (RuntimeException cause) {
            attemptService.recordFailure(request.username());
            throw cause;
        }
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public PasswordResetResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        // Recovery is intentionally offline: contact the center administrator.
        return new PasswordResetResponse(false,
            "Pide al administrador de tu centro que restablezca tu contraseña; este sistema no la envía por correo.");
    }
}