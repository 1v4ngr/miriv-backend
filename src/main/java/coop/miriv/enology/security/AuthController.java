package coop.miriv.enology.security;

import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.security.dto.LoginRequest;
import coop.miriv.enology.security.dto.LoginResponse;
import coop.miriv.enology.security.dto.PasswordResetRequest;
import coop.miriv.enology.security.dto.PasswordResetResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            throw new AccountLockedException();
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
        } catch (BadCredentialsException | DisabledException cause) {
            // Only an actual wrong-credentials/disabled-account response counts as a failed
            // attempt; an infrastructure error (e.g. the database being briefly unreachable)
            // must not eat into the person's attempt budget.
            attemptService.recordFailure(request.username());
            throw new InvalidCredentialsException(attemptService.attemptsRemaining(request.username()));
        }
    }

    @PostMapping("/password-reset")
    public PasswordResetResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        // Recovery is intentionally offline: contact the center administrator. This is a
        // regular 200 response (ok=false is informational, not an HTTP-level failure) so the
        // front does not mistake "the endpoint exists but recovery is manual" for a 404.
        return new PasswordResetResponse(false,
            "Pide al administrador de tu centro que restablezca tu contraseña; este sistema no la envía por correo.");
    }
}
