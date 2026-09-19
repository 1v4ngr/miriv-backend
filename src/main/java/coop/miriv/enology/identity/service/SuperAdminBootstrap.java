package coop.miriv.enology.identity.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure the configured owner account holds SUPER_ADMIN (global, all zones) on every start.
 * It only adds the role; revoking it is done from the admin UI by another super administrator.
 */
@Component
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final String email;

    public SuperAdminBootstrap(JdbcTemplate jdbc, @Value("${app.security.super-admin-email:}") String email) {
        this.jdbc = jdbc;
        this.email = email == null ? "" : email.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty()) return;
        List<UUID> users = jdbc.query("select id from app_user where lower(email) = lower(?)",
            (rs, n) -> rs.getObject(1, UUID.class), email);
        if (users.isEmpty()) {
            log.warn("No existe ninguna cuenta con el email {} para hacerla superadministradora.", email);
            return;
        }
        int added = jdbc.update("""
            insert into app_user_role (id, user_id, role_id, zone_id)
            select ?, ?, r.id, null from role r
             where r.code = 'SUPER_ADMIN'
               and not exists (select 1 from app_user_role ur
                                where ur.user_id = ? and ur.role_id = r.id and ur.zone_id is null)
            """, UUID.randomUUID(), users.getFirst(), users.getFirst());
        if (added > 0) log.info("Rol SUPER_ADMIN asignado a {}.", email);
    }
}
