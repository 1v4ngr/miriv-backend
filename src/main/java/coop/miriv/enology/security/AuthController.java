package coop.miriv.enology.security;

import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.security.dto.LoginRequest;
import coop.miriv.enology.security.dto.LoginResponse;
import coop.miriv.enology.security.dto.PasswordResetRequest;
import coop.miriv.enology.security.dto.PasswordResetResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService attemptService;
    private final UserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          LoginAttemptService attemptService, UserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.attemptService = attemptService;
        this.userDetailsService = userDetailsService;
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

    /**
     * Renews the access token (even one that just expired), so an open session is not cut every few minutes. The user is read
     * again (a deactivated account or changed permissions take effect at once) and renewals stop
     * {@code session-max-hours} after the original sign-in, when the person has to sign in again.
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestHeader(value = "Authorization", required = false) String header) {
        if (header == null || !header.startsWith("Bearer ")) throw new CredentialsExpiredException("Sin sesión que renovar.");
        Claims claims;
        try {
            claims = jwtService.parseClaims(header.substring("Bearer ".length()));
        } catch (ExpiredJwtException expired) {
            // The signature was verified before the expiry check: an expired token of ours (the laptop slept,
            // the tab was idle) may still be renewed while the session is within its maximum length.
            claims = expired.getClaims();
        } catch (JwtException | IllegalArgumentException invalid) {
            throw new CredentialsExpiredException("La sesión no es válida.");
        }
        Number signedIn = claims.get(JwtService.AUTH_TIME, Number.class);
        // Tokens issued before renewals existed carry no sign-in time: their own issue time stands in for it.
        Instant authTime = signedIn != null ? Instant.ofEpochSecond(signedIn.longValue()) : claims.getIssuedAt().toInstant();
        if (Instant.now().isAfter(jwtService.sessionDeadline(authTime))) throw new CredentialsExpiredException("La sesión ha llegado a su duración máxima.");
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(claims.getSubject());
        } catch (UsernameNotFoundException gone) {
            throw new CredentialsExpiredException("La cuenta ya no está disponible.");
        }
        if (!user.isEnabled() || !user.isAccountNonLocked()) throw new CredentialsExpiredException("La cuenta está desactivada.");
        AppUserPrincipal principal = (AppUserPrincipal) user;
        var authorities = user.getAuthorities().stream().map(Object::toString).toList();
        String token = jwtService.issueAccessToken(principal.getUsername(), authorities, authTime);
        return new LoginResponse(token, jwtService.accessTokenValiditySeconds(), principal.getUserId(),
            principal.getUser().getFullName(), authorities);
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
