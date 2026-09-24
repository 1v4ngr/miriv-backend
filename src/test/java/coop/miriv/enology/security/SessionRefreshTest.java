package coop.miriv.enology.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** POST /api/auth/refresh: an open session renews its token, within the maximum session length. */
class SessionRefreshTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac-sha-256!!";
    private final JwtService jwt = new JwtService(new JwtProperties(SECRET, 30, "miriv-test", 12L));
    private AppUser user;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setUsername("enologo");
        user.setFullName("Elena Enóloga");
        user.setActive(true);
        controller = new AuthController(null, jwt, null, username -> {
            if (!username.equals("enologo")) throw new UsernameNotFoundException(username);
            return new AppUserPrincipal(user);
        });
    }

    /** A token signed with our key, issued/expiring at the given instants, for a sign-in at {@code authTime}. */
    private String token(Instant issuedAt, Instant expiresAt, Instant authTime, String secret) {
        return Jwts.builder().issuer("miriv-test").subject("enologo").claim("authorities", List.of())
            .claim(JwtService.AUTH_TIME, authTime.getEpochSecond())
            .issuedAt(Date.from(issuedAt)).expiration(Date.from(expiresAt))
            .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test
    void renewsAValidTokenKeepingTheOriginalSignInTime() {
        Instant signedIn = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String current = token(Instant.now().minusSeconds(60), Instant.now().plusSeconds(120), signedIn, SECRET);

        String renewed = controller.refresh("Bearer " + current).accessToken();

        var claims = jwt.parseClaims(renewed);
        assertThat(claims.getSubject()).isEqualTo("enologo");
        assertThat(claims.get(JwtService.AUTH_TIME, Number.class).longValue()).isEqualTo(signedIn.getEpochSecond());
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now().plus(29, ChronoUnit.MINUTES));
    }

    @Test
    void renewsATokenThatJustExpiredWhileTheSessionIsWithinItsMaximum() {
        Instant signedIn = Instant.now().minus(3, ChronoUnit.HOURS);
        String expired = token(Instant.now().minus(90, ChronoUnit.MINUTES), Instant.now().minus(60, ChronoUnit.MINUTES), signedIn, SECRET);

        assertThat(controller.refresh("Bearer " + expired).accessToken()).isNotBlank();
    }

    @Test
    void stopsRenewingAfterTheMaximumSessionLength() {
        Instant signedIn = Instant.now().minus(13, ChronoUnit.HOURS);
        String current = token(Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), signedIn, SECRET);

        assertThatThrownBy(() -> controller.refresh("Bearer " + current)).isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    void refusesATokenNotSignedByUs() {
        String forged = token(Instant.now(), Instant.now().plusSeconds(600), Instant.now(), "another-secret-that-is-long-enough-for-hmac-256!!");

        assertThatThrownBy(() -> controller.refresh("Bearer " + forged)).isInstanceOf(CredentialsExpiredException.class);
        assertThatThrownBy(() -> controller.refresh(null)).isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    void refusesADeactivatedAccount() {
        user.setActive(false);
        String current = token(Instant.now(), Instant.now().plusSeconds(600), Instant.now(), SECRET);

        assertThatThrownBy(() -> controller.refresh("Bearer " + current)).isInstanceOf(CredentialsExpiredException.class);
    }
}
