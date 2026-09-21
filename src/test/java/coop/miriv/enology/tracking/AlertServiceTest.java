package coop.miriv.enology.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.cellar.dto.StateReviewRequest;
import coop.miriv.enology.cellar.service.ContentService;
import coop.miriv.enology.support.IntegrationTest;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertCondition;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertRuleRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertView;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRequest;
import coop.miriv.enology.tracking.service.AlertService;
import coop.miriv.enology.tracking.service.ParameterTargetService;
import coop.miriv.enology.tracking.service.TrackingService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Alerts ("fermentation finished"…), acknowledgement, scoping and the values fixed for one content. */
@Transactional
class AlertServiceTest extends IntegrationTest {

    private static final UUID CENTER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FINISHED = "Fermentación alcohólica terminada";

    @Autowired AlertService alerts;
    @Autowired ContentService contents;
    @Autowired ParameterTargetService targets;
    @Autowired TrackingService tracking;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();
    private UUID admin;
    private UUID zone;
    private int sequence;

    @BeforeEach
    void setUp() {
        actAs("enologo");
        admin = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        zone = UUID.randomUUID();
        jdbc.update("insert into zone(id, center_id, code, name) values (?, ?, 'Z-ALR', 'Zona avisos')", zone, CENTER);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private UUID tank(String suffix, String category, String phase) {
        UUID categoryId = jdbc.queryForObject("select id from internal_category where code = ?", UUID.class, category);
        UUID destination = jdbc.queryForObject("select id from destination limit 1", UUID.class);
        UUID lot = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID deposit = UUID.randomUUID();
        jdbc.update("insert into deposit(id, code, center_id, zone_id, useful_capacity_liters) values (?, ?, ?, ?, 10000)", deposit, "D-" + suffix, CENTER, zone);
        jdbc.update("insert into lot(id, code, campaign, category_id, destination_id, entry_date, responsible_id, center_id) "
            + "values (?, ?, 2026, ?, ?, current_date, ?, ?)", lot, "L-" + suffix, categoryId, destination, admin, CENTER);
        jdbc.update("insert into content_unit(id, code, lot_id, category_id, volume_liters) values (?, ?, ?, ?, 1000)", content, "C-" + suffix, lot, categoryId);
        jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, 1000)",
            UUID.randomUUID(), content, deposit, Timestamp.from(now.minus(20, ChronoUnit.DAYS)));
        if (phase != null) {
            jdbc.update("insert into fermentation_state(id, content_unit_id, process, estimated_status, estimated_at) "
                + "values (?, ?, 'ALCOHOLIC'::fermentation_process, ?, now())", UUID.randomUUID(), content, phase);
        }
        return content;
    }

    /** One analysis (validated) taken `daysAgo` days ago with a single result. */
    private String sample(UUID content, String parameter, String value, double daysAgo) {
        String code = "S-ALR-" + (++sequence);
        UUID sample = UUID.randomUUID();
        Instant takenAt = now.minus((long) (daysAgo * 24 * 60), ChronoUnit.MINUTES);
        jdbc.update("insert into sample(id, code, content_unit_id, occupation_id, deposit_id_at_sampling, taken_at, taken_by_id) "
            + "select ?, ?, ?, o.id, o.deposit_id, ?, ? from occupation o where o.content_unit_id = ?", sample, code, content, Timestamp.from(takenAt), admin, content);
        jdbc.update("insert into analysis(id, sample_id, status) values (?, ?, 'VALIDATED'::analysis_status)", UUID.randomUUID(), sample);
        jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, is_current, created_by_id) "
                + "select ?, a.id, p.id, 'NONE'::result_qualifier, ?::numeric, true, ? from analysis a, parameter p where a.sample_id = ? and p.code = ?",
            UUID.randomUUID(), value, admin, sample, parameter);
        return code;
    }

    private List<AlertView> forRule(String rule) {
        return alerts.alerts().stream().filter(alert -> alert.rule().equals(rule)).toList();
    }

    private void steadyLowDensity(UUID content) {
        sample(content, "DENSITY", "0.9962", 3.5);
        sample(content, "DENSITY", "0.9956", 2.5);
        sample(content, "DENSITY", "0.9953", 1.5);
        sample(content, "DENSITY", "0.9951", 0.5);
    }

    @Test
    void lowAndStableDensityRaisesTheFinishedFermentationAlert() {
        UUID content = tank("A1", "RED", "ACTIVE");
        steadyLowDensity(content);

        var hits = forRule(FINISHED);

        assertEquals(1, hits.size());
        assertEquals("C-A1", hits.getFirst().content());
        assertEquals("INFO", hits.getFirst().severity());
        assertTrue(hits.getFirst().detail().contains("Densidad"), hits.getFirst().detail());
        assertTrue(hits.getFirst().detail().contains("estable"));
    }

    @Test
    void aFermentationThatIsStillDroppingRaisesNothing() {
        UUID content = tank("A2", "RED", "ACTIVE");
        sample(content, "DENSITY", "1.0200", 2);
        sample(content, "DENSITY", "1.0000", 1);
        sample(content, "DENSITY", "0.9970", 0);
        assertTrue(forRule(FINISHED).isEmpty(), "0.997 is low but the density is still moving");
    }

    @Test
    void confirmingTheStateKeepsThePhaseRulesWorking() {
        // Regression: confirmations were stored as "Activa" and never matched the rule's ACTIVE phase,
        // so the finished-fermentation alert went silent as soon as an enologist confirmed the state.
        UUID content = tank("A9", "RED", null);
        contents.review("C-A9", new StateReviewRequest("alcoholic", "Activa", "Burbujeo y densidad bajando", null));
        steadyLowDensity(content);

        assertEquals(1, forRule(FINISHED).size(), "the rule must still see the confirmed ACTIVE phase");
    }

    @Test
    void theRuleOnlyAppliesToTheConfiguredFermentationPhases() {
        UUID finished = tank("A3", "RED", "FINISHED");
        steadyLowDensity(finished);
        assertTrue(forRule(FINISHED).isEmpty(), "already finished wines are not announced again");
    }

    @Test
    void acknowledgingHidesTheAlertUntilANewerSampleRaisesItAgain() {
        UUID content = tank("A4", "RED", "ACTIVE");
        steadyLowDensity(content);
        AlertView alert = forRule(FINISHED).getFirst();

        alerts.acknowledge(alert.ruleId(), "C-A4");
        assertTrue(forRule(FINISHED).isEmpty());

        sample(content, "DENSITY", "0.9949", 0);
        assertEquals(1, forRule(FINISHED).size(), "a new sample brings it back");
        alerts.acknowledge(alert.ruleId(), "C-A4");   // acknowledging again is fine
    }

    @Test
    void sugarsRuleAndTheSeverityOrder() {
        UUID content = tank("A5", "RED", "SLOW");
        sample(content, "REDUCING_SUGARS", "1.4", 0);
        sample(content, "DENSITY", "1.0300", 3);
        sample(content, "DENSITY", "1.0290", 2);
        sample(content, "DENSITY", "1.0290", 1);
        sample(content, "DENSITY", "1.0291", 0);

        var all = alerts.alerts().stream().filter(alert -> alert.content().equals("C-A5")).toList();

        assertEquals(List.of("WARN", "INFO"), all.stream().map(AlertView::severity).toList(), "stopped fermentation (warn) first, then the sugars (info)");
        assertEquals("Posible parada de fermentación", all.get(0).rule());
        assertEquals("Azúcares agotados", all.get(1).rule());
    }

    @Test
    void aRuleFixedForOneContentOnlyWatchesThatContent() {
        UUID first = tank("A6", "RED", "ACTIVE");
        UUID second = tank("A7", "RED", "ACTIVE");
        sample(first, "VOLATILE_ACIDITY", "0.65", 0);
        sample(second, "VOLATILE_ACIDITY", "0.66", 0);

        alerts.create(new AlertRuleRequest("AV alta en A7", "WARN",
            List.of(new AlertCondition("VOLATILE_ACIDITY", "GTE", new BigDecimal("0.5"), null, null)), null, "C-A7", List.of(), true));
        alerts.create(new AlertRuleRequest("AV alta en tintos", "CRIT",
            List.of(new AlertCondition("VOLATILE_ACIDITY", "GTE", new BigDecimal("0.7"), null, null)), "WHITE", null, List.of(), true));

        assertEquals(List.of("C-A7"), forRule("AV alta en A7").stream().map(AlertView::content).toList());
        assertTrue(forRule("AV alta en tintos").isEmpty(), "white-only rule ignores red wines");
        assertEquals(1, alerts.rulesForContent("C-A7").size());
        assertTrue(alerts.rulesForContent("C-A6").isEmpty());
    }

    @Test
    void invalidRulesAreRejected() {
        var ok = List.of(new AlertCondition("DENSITY", "LTE", new BigDecimal("1"), null, null));
        assertThrows(BusinessRuleException.class, () -> alerts.create(new AlertRuleRequest("", "INFO", ok, null, null, null, true)));
        assertThrows(BusinessRuleException.class, () -> alerts.create(new AlertRuleRequest("X", "GRAVE", ok, null, null, null, true)));
        assertThrows(BusinessRuleException.class, () -> alerts.create(new AlertRuleRequest("X", "INFO", List.of(), null, null, null, true)));
        assertThrows(BusinessRuleException.class, () -> alerts.create(new AlertRuleRequest("X", "INFO",
            List.of(new AlertCondition("DENSITY", "STABLE", null, 0, new BigDecimal("0.001"))), null, null, null, true)));
        assertThrows(BusinessRuleException.class, () -> alerts.create(new AlertRuleRequest("X", "INFO",
            List.of(new AlertCondition("DENSITY", "LTE", null, null, null)), null, null, null, true)));
    }

    @Test
    void valuesFixedForOneContentBeatTheGlobalRangeOnlyForThatContent() {
        UUID first = tank("A8", "RED", "ACTIVE");
        UUID second = tank("A9", "RED", "ACTIVE");
        sample(first, "VOLATILE_ACIDITY", "0.45", 0);
        sample(second, "VOLATILE_ACIDITY", "0.45", 0);

        var saved = targets.saveForContent("C-A8", new TargetRequest("VOLATILE_ACIDITY", null, null, null, null, new BigDecimal("0.4"), null, new BigDecimal("0.5"), "Este tinto va fino"));
        assertEquals("C-A8", saved.contentCode());

        var mine = tracking.latest(List.of("C-A8")).getFirst().targets().stream().filter(t -> t.parameter().equals("VOLATILE_ACIDITY")).findFirst().orElseThrow();
        var other = tracking.latest(List.of("C-A9")).getFirst().targets().stream().filter(t -> t.parameter().equals("VOLATILE_ACIDITY")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.4").compareTo(mine.warnMax()));
        assertEquals(0, new BigDecimal("0.6").compareTo(other.warnMax()), "the other tank keeps the global 0.6");

        var updated = targets.saveForContent("C-A8", new TargetRequest("VOLATILE_ACIDITY", null, null, null, null, new BigDecimal("0.35"), null, new BigDecimal("0.5"), null));
        assertEquals(saved.id(), updated.id(), "one fixed range per parameter and content");
        assertEquals(1, targets.listForContent("C-A8").size());
        assertTrue(targets.listForContent("C-A9").isEmpty());

        targets.deleteForContent("C-A8", saved.id());
        assertFalse(targets.listForContent("C-A8").stream().anyMatch(t -> t.id().equals(saved.id())));
        assertThrows(coop.miriv.enology.common.exception.NotFoundException.class, () -> targets.deleteForContent("C-A9", saved.id()));
    }
}
