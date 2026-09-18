package coop.miriv.enology.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Brute-force throttle: 5 failed attempts per username ⇒ 15 minute lockout. */
@Service
public class LoginAttemptService {

    public static final int FAILURE_LIMIT = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    public LoginAttemptService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isLocked(String username) {
        Instant cutoff = Instant.now().minus(LOCK_DURATION);
        Integer recentFailures = jdbc.queryForObject("select count(*) from login_attempt "
                + "where username = lower(?) and successful = false and attempted_at > ?",
            Integer.class, username, cutoff);
        return recentFailures != null && recentFailures >= FAILURE_LIMIT;
    }

    public void recordFailure(String username) {
        jdbc.update("insert into login_attempt(username, successful) values (lower(?), false)", username);
    }

    public void recordSuccess(String username) {
        jdbc.update("insert into login_attempt(username, successful) values (lower(?), true)", username);
    }
}