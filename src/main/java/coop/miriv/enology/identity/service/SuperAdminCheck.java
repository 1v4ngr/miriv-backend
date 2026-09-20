package coop.miriv.enology.identity.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Destructive corrections (deleting an analysis, undoing a movement) are reserved to the super
 * administrator: the role is checked live against the database, never taken from the token.
 */
@Service
public class SuperAdminCheck {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;

    public SuperAdminCheck(JdbcTemplate jdbc, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public boolean isSuperAdmin() {
        UUID userId = context.userId();
        return userId != null && Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from app_user_role ur join role r on r.id = ur.role_id "
                + "where ur.user_id = ? and r.code = 'SUPER_ADMIN')", Boolean.class, userId));
    }

    public void require(String action) {
        if (!isSuperAdmin()) {
            throw new AccessDeniedException("Solo un superadministrador puede " + action + ".");
        }
    }
}
