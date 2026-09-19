package coop.miriv.enology.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.identity.service.CenterPurgeService;
import coop.miriv.enology.identity.service.SuperAdminBootstrap;
import coop.miriv.enology.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Deleting a center with everything in it: SUPER_ADMIN only, confirmed by code, nothing left dangling. */
@Transactional
class CenterPurgeTest extends IntegrationTest {

    @Autowired CenterPurgeService purge;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private String seededCenter() {
        return jdbc.queryForObject("select c.code from center c join app_user u on u.center_id = c.id where u.username = 'admin'", String.class);
    }

    @Test
    void onlySuperAdminCanPurgeAndMustTypeTheCode() {
        actAs("admin");
        String code = seededCenter();
        assertFalse(purge.impact(code).canPurge());
        assertThrows(AccessDeniedException.class, () -> purge.purge(code, code));
        new SuperAdminBootstrap(jdbc, "admin@miriv.local").run(null);
        actAs("admin");
        assertThrows(BusinessRuleException.class, () -> purge.purge(code, "otro"));
        // Its only center: purging would delete the acting account.
        assertThrows(BusinessRuleException.class, () -> purge.purge(code, code));
    }

    @Test
    void purgesTheCenterWithEverythingInIt() {
        String code = seededCenter();
        UUID other = UUID.randomUUID();
        jdbc.update("insert into center(id, code, name) values (?, 'OTRO', 'Otro centro')", other);
        jdbc.update("insert into app_user_center(user_id, center_id) select id, ? from app_user where username = 'admin'", other);
        new SuperAdminBootstrap(jdbc, "admin@miriv.local").run(null);
        actAs("admin");

        var impact = purge.impact(code);
        assertTrue(impact.canPurge());
        assertTrue(impact.usersDeleted().contains("enologo"));

        purge.purge(code, code.toLowerCase());

        assertEquals(0, jdbc.queryForObject("select count(*) from center where code = ?", Integer.class, code));
        assertEquals(0, jdbc.queryForObject("select count(*) from app_user where username = 'enologo' and active", Integer.class));
        assertEquals(other, jdbc.queryForObject("select center_id from app_user where username = 'admin'", UUID.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_log where action = 'CENTER_PURGED'", Integer.class));
    }
}
