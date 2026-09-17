package coop.miriv.enology.security;

import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.security.dto.LoginRequest;
import coop.miriv.enology.security.dto.LoginResponse;
import coop.miriv.enology.security.dto.PasswordResetRequest;
import coop.miriv.enology.security.dto.PasswordResetResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
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

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        var authorities = authentication.getAuthorities().stream().map(Object::toString).toList();
        String token = jwtService.issueAccessToken(principal.getUsername(), authorities);
        return new LoginResponse(token, jwtService.accessTokenValiditySeconds(), principal.getUserId(),
            principal.getUser().getFullName(), authorities);
    }

    @PostMapping("/password-reset")
    public PasswordResetResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        // Intentionally generic: account existence is never revealed through this endpoint.
        return new PasswordResetResponse(true,
            "If an account matches the provided identifier, recovery instructions will be sent.");
    }
}
