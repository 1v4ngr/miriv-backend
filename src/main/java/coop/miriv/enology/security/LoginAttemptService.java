package coop.miriv.enology.security;

import coop.miriv.enology.identity.repository.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Brute-force throttle: 5 failed attempts per account since the last success ⇒ 15 minute lockout. */
@Service
public class LoginAttemptService {

    public static final int FAILURE_LIMIT = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public LoginAttemptService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public boolean isLocked(String usernameOrEmail) {
        return recentFailures(usernameOrEmail) >= FAILURE_LIMIT;
    }

    /** Failed attempts remaining before the account locks (0 once already locked). */
    public int attemptsRemaining(String usernameOrEmail) {
        return Math.max(0, FAILURE_LIMIT - recentFailures(usernameOrEmail));
    }

    private int recentFailures(String usernameOrEmail) {
        String key = canonicalKey(usernameOrEmail);
        Instant cutoff = Instant.now().minus(LOCK_DURATION);
        // Only failures AFTER the most recent success (or ever, if there was none) count:
        // a correct login always clears the counter, it never merely pauses it.
        Integer count = jdbc.queryForObject("""
            select count(*) from login_attempt
             where username = ? and successful = false and attempted_at > ?
               and attempted_at > coalesce(
                     (select max(attempted_at) from login_attempt where username = ? and successful = true),
                     '-infinity'::timestamptz)
            """, Integer.class, key, Timestamp.from(cutoff), key);
        return count == null ? 0 : count;
    }

    public void recordFailure(String usernameOrEmail) {
        jdbc.update("insert into login_attempt(username, successful) values (?, false)", canonicalKey(usernameOrEmail));
    }

    public void recordSuccess(String usernameOrEmail) {
        jdbc.update("insert into login_attempt(username, successful) values (?, true)", canonicalKey(usernameOrEmail));
    }

    /**
     * Resolves whatever the person typed (username or email) to the account's actual username,
     * so "admin" and "admin@miriv.local" share one counter instead of doubling the attempt budget.
     * An unknown login is still throttled under its own normalized key.
     */
    private String canonicalKey(String usernameOrEmail) {
        String normalized = usernameOrEmail == null ? "" : usernameOrEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return normalized;
        return users.findByUsernameAndActiveTrue(normalized)
            .or(() -> users.findByEmailIgnoreCaseAndActiveTrue(normalized))
            .map(user -> user.getUsername().toLowerCase(Locale.ROOT))
            .orElse(normalized);
    }
}
