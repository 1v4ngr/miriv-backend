package coop.miriv.enology.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Refuses to start outside dev/test while any active account still uses the published sample password. */
@Component
@Profile("!dev & !test")
public class DefaultCredentialsGuard implements ApplicationRunner {

    private static final String SAMPLE_PASSWORD = "ChangeMe123!";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DefaultCredentialsGuard(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        var exposed = jdbc.query("select username, password_hash from app_user where active = true",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}).stream()
            .filter(user -> encoder.matches(SAMPLE_PASSWORD, user[1]))
            .map(user -> user[0])
            .toList();
        if (!exposed.isEmpty()) {
            throw new IllegalStateException("Accounts still use the sample password: " + exposed
                + ". Change their password or deactivate them before starting in this environment.");
        }
    }
}