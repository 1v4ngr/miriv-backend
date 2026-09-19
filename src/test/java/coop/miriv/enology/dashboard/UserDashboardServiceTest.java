package coop.miriv.enology.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardRequest;
import coop.miriv.enology.dashboard.service.UserDashboardService;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F6-01: personal dashboards, scoped per user, optimistic locking and limits. */
@Transactional
class UserDashboardServiceTest extends IntegrationTest {

    @Autowired UserDashboardService dashboards;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void asEnologist() { actAs("enologo"); }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private JsonNode widgets(int count) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < count; i++) text.append(i > 0 ? "," : "").append("{\"id\":\"w").append(i).append("\",\"type\":\"chart\"}");
        return json.readTree(text.append("]").toString());
    }

    private DashboardRequest request(String name, int widgetCount, Integer version) {
        return new DashboardRequest(name, 1, json.readTree("{\"lg\":[]}"), widgets(widgetCount), version);
    }

    @Test
    void listCreatesADefaultDashboardTheFirstTime() {
        var first = dashboards.list();
        assertEquals(1, first.size());
        assertTrue(first.getFirst().isDefault());
        assertEquals(1, dashboards.list().size());
    }

    @Test
    void usersOnlySeeTheirOwnDashboards() {
        var mine = dashboards.create(request("Tintos", 1, null));
        actAs("laboratorio");
        assertThrows(NotFoundException.class, () -> dashboards.get(mine.id()));
        assertThrows(NotFoundException.class, () -> dashboards.delete(mine.id()));
        assertFalse(dashboards.list().stream().anyMatch(item -> item.id().equals(mine.id())));
    }

    @Test
    void updateRequiresTheCurrentVersion() {
        var created = dashboards.create(request("Tintos", 1, null));
        assertEquals(0, created.version());
        var updated = dashboards.update(created.id(), request("Tintos", 2, 0));
        assertEquals(1, updated.version());
        assertEquals(2, updated.widgets().size());
        var stale = request("Tintos", 3, 0);
        var error = assertThrows(ConflictException.class, () -> dashboards.update(created.id(), stale));
        assertEquals("STALE_DASHBOARD", error.getCode());
        assertThrows(BusinessRuleException.class, () -> dashboards.update(created.id(), request("Tintos", 1, null)));
    }

    @Test
    void rejectsInvalidOrTooManyWidgets() {
        assertThrows(BusinessRuleException.class, () -> dashboards.create(request("Muchos", 31, null)));
        assertEquals(30, dashboards.create(request("Justo", 30, null)).widgets().size());
        var notAnArray = new DashboardRequest("Raro", 1, null, json.readTree("{\"a\":1}"), null);
        assertThrows(BusinessRuleException.class, () -> dashboards.create(notAnArray));
        var noType = new DashboardRequest("Sin tipo", 1, null, json.readTree("[{\"id\":\"a\"}]"), null);
        assertThrows(BusinessRuleException.class, () -> dashboards.create(noType));
    }

    @Test
    void namesAreUniquePerUserIgnoringCase() {
        dashboards.create(request("Tintos", 0, null));
        assertThrows(ConflictException.class, () -> dashboards.create(request("tintos", 0, null)));
        actAs("laboratorio");
        dashboards.create(request("Tintos", 0, null));   // another user may reuse the name
    }

    @Test
    void duplicateAndDefault() {
        dashboards.list();
        var original = dashboards.create(request("Tintos", 2, null));
        var copy = dashboards.duplicate(original.id());
        assertEquals("Tintos (copia)", copy.name());
        assertEquals("Tintos (copia 2)", dashboards.duplicate(original.id()).name());
        assertEquals(2, copy.widgets().size());
        assertTrue(dashboards.makeDefault(copy.id()).isDefault());
        assertEquals(1, jdbc.queryForObject("select count(*) from user_dashboard u join app_user a on a.id = u.user_id "
            + "where a.username = 'enologo' and u.is_default", Integer.class));
    }

    @Test
    void cannotDeleteTheLastOneAndDefaultMoves() {
        var only = dashboards.list().getFirst();
        assertThrows(BusinessRuleException.class, () -> dashboards.delete(only.id()));
        var other = dashboards.create(request("Otro", 0, null));
        dashboards.delete(only.id());                       // it was the default: the remaining one takes over
        assertTrue(dashboards.get(other.id()).isDefault());
    }
}
