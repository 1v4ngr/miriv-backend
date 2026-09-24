package coop.miriv.enology.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.jwt")
/**
 * @param accessTokenMinutes lifetime of one access token; the front renews it before it runs out
 * @param sessionMaxHours    absolute limit of a session: renewals stop this long after the original sign-in
 */
public record JwtProperties(String secret, long accessTokenMinutes, String issuer, Long sessionMaxHours) {

    public long sessionMaxHoursOrDefault() {
        return sessionMaxHours == null || sessionMaxHours <= 0 ? 12 : sessionMaxHours;
    }
}
