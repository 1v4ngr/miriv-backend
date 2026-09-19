package coop.miriv.enology.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.identity.dto.PermissionGrantRequest;
import coop.miriv.enology.identity.dto.RoleAssignmentRequest;
import coop.miriv.enology.identity.dto.UserAccountResponse;
import coop.miriv.enology.identity.dto.UserAccountStatusRequest;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.identity.service.UserAccountService;
import coop.miriv.enology.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** F1C-06 + review D1/D2/D3: account administration works and its safeguards hold. */
@Transactional
class UserAccountServiceTest extends IntegrationTest {

    @Autowired UserAccountService accounts;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void authenticateAsAdmin() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername("admin");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private UUID id(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", UUID.class, username);
    }

    @Test
    void listsAccountsWithDisplayNames() {
        var list = accounts.list();
        assertTrue(list.stream().anyMatch(account -> account.username().equals("enologo")
            && account.displayName().equals("Elena Enóloga")));
    }

    @Test
    void grantsAndRevokesAnIndividualPermissionWithAudit() {
        UserAccountResponse granted = accounts.grantPermission(id("enologo"),
            new PermissionGrantRequest("RESULT_ENTER", null, "El enólogo hace sus analíticas", null, null));
        assertTrue(granted.grants().stream().anyMatch(grant -> grant.permissionCode().equals("RESULT_ENTER")));
        UUID grantId = granted.grants().getFirst().id();
        accounts.revokePermission(id("enologo"), grantId);
        Integer audits = jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action in ('GRANT', 'REVOKE')",
            Integer.class, id("enologo"));
        assertEquals(2, audits);
    }

    @Test
    void refusesNonGrantablePermissions() {
        assertThrows(ConflictException.class, () -> accounts.grantPermission(id("enologo"),
            new PermissionGrantRequest("STATE_CONFIRM", null, "No se concede suelto", null, null)));
    }

    @Test
    void anAdminCannotChangeTheirOwnAccess() {
        assertThrows(ConflictException.class, () -> accounts.assignRole(id("admin"), new RoleAssignmentRequest("ENOLOGIST", null)));
        assertThrows(ConflictException.class, () -> accounts.grantPermission(id("admin"),
            new PermissionGrantRequest("RESULT_ENTER", null, "Autoconcesión", null, null)));
    }

    @Test
    void keepsAtLeastOneActiveAdmin() {
        // "produccion" becomes the only other admin; "admin" (acting user) is then switched off in the DB,
        // so removing ADMIN from "produccion" would leave nobody able to administer.
        jdbc.update("insert into app_user_role(id, user_id, role_id) select ?, ?, id from role where code = 'ADMIN'",
            UUID.randomUUID(), id("produccion"));
        UUID assignment = jdbc.queryForObject("select ur.id from app_user_role ur join role r on r.id = ur.role_id "
            + "where ur.user_id = ? and r.code = 'ADMIN'", UUID.class, id("produccion"));
        jdbc.update("update app_user set active = false where username = 'admin'");
        assertThrows(BusinessRuleException.class, () -> accounts.revokeRole(id("produccion"), assignment));
        assertThrows(BusinessRuleException.class, () -> accounts.deactivate(id("produccion"), new UserAccountStatusRequest("Prueba")));
    }
}
