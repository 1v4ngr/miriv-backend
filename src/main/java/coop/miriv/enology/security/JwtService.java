package coop.miriv.enology.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Claim with the instant of the original sign-in (epoch seconds), carried over by every renewal. */
    public static final String AUTH_TIME = "auth_time";

    public String issueAccessToken(String username, List<String> authorities) {
        return issueAccessToken(username, authorities, Instant.now());
    }

    /** A token for a session that started at {@code authTime} (sign-in, or the sign-in a renewal belongs to). */
    public String issueAccessToken(String username, List<String> authorities, Instant authTime) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
            .issuer(properties.issuer())
            .subject(username)
            .claim("authorities", authorities)
            .claim(AUTH_TIME, authTime.getEpochSecond())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /** Latest instant a session that started at {@code authTime} may still be renewed. */
    public Instant sessionDeadline(Instant authTime) {
        return authTime.plus(properties.sessionMaxHoursOrDefault(), ChronoUnit.HOURS);
    }

    public long accessTokenValiditySeconds() {
        return properties.accessTokenMinutes() * 60;
    }
}
