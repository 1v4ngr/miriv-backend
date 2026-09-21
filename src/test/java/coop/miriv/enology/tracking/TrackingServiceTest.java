package coop.miriv.enology.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.support.IntegrationTest;
import coop.miriv.enology.tracking.dto.TrackingDto.OverviewRow;
import coop.miriv.enology.tracking.dto.TrackingDto.SeriesResponse;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRequest;
import coop.miriv.enology.tracking.service.ParameterTargetService;
import coop.miriv.enology.tracking.service.ParameterTargetService.Target;
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

/** Tracking read models: series, events, overview and editable targets. */
@Transactional
class TrackingServiceTest extends IntegrationTest {

    private static final UUID CENTER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired TrackingService tracking;
    @Autowired ParameterTargetService targets;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();
    private UUID admin;
    private UUID zone;
    private UUID content1;
    private UUID content2;

    @BeforeEach
    void setUp() {
        actAs("admin");
        admin = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        zone = UUID.randomUUID();
        jdbc.update("insert into zone(id, center_id, code, name) values (?, ?, 'Z-TRK', 'Zona seguimiento')", zone, CENTER);
        UUID category = jdbc.queryForObject("select id from internal_category where code = 'RED'", UUID.class);
        UUID color = UUID.randomUUID();
        UUID destination = jdbc.queryForObject("select id from destination limit 1", UUID.class);
        content1 = content("T1", category, color, destination);
        content2 = content("T2", category, color, destination);

        // T1: acidity 0.30 (ten days ago) then 0.72 (now), pH 3.42; T2: acidity 0.35; plus noise that must be ignored.
        UUID s1 = sample("S-T1-A", content1, now.minus(10, ChronoUnit.DAYS), "VALIDATED");
        result(s1, "VOLATILE_ACIDITY", "0.30", "NONE", null, true);
        UUID s2 = sample("S-T1-B", content1, now.minus(1, ChronoUnit.HOURS), "VALIDATED");
        result(s2, "VOLATILE_ACIDITY", "0.72", "NONE", null, true);
        result(s2, "PH", "3.42", "NONE", null, true);
        result(s2, "VOLATILE_ACIDITY", "9.99", "NONE", null, false);           // superseded → not current
        UUID s3 = sample("S-T1-C", content1, now.minus(2, ChronoUnit.DAYS), "INVALIDATED");
        result(s3, "VOLATILE_ACIDITY", "5.00", "NONE", null, true);            // invalidated analysis
        UUID s4 = sample("S-T2-A", content2, now.minus(3, ChronoUnit.DAYS), "PENDING_VALIDATION");
        result(s4, "VOLATILE_ACIDITY", null, "LESS_THAN", "0.10", true);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void seriesReturnsOnlyCurrentValidResultsOfTheRequestedContentsAndParameters() {
        SeriesResponse series = tracking.series(List.of("C-T1", "C-T2"), List.of("VOLATILE_ACIDITY", "PH"), null, null, false);

        assertEquals(List.of("C-T1", "C-T2"), series.contents().stream().map(c -> c.code()).toList());
        assertEquals(List.of("VOLATILE_ACIDITY", "PH"), series.parameters().stream().map(p -> p.code()).toList());
        var acidityT1 = series.points().stream()
            .filter(p -> p.content().equals("C-T1") && p.parameter().equals("VOLATILE_ACIDITY")).toList();
        assertEquals(2, acidityT1.size());                                       // 9.99 (not current) and 5.00 (invalidated) excluded
        assertEquals(0, new BigDecimal("0.30").compareTo(acidityT1.get(0).value()));   // ordered by sampling time
        assertEquals(0, new BigDecimal("0.72").compareTo(acidityT1.get(1).value()));
        var qualified = series.points().stream().filter(p -> p.content().equals("C-T2")).findFirst().orElseThrow();
        assertEquals("LESS_THAN", qualified.qualifier());
        assertNull(qualified.value());
        assertEquals(0, new BigDecimal("0.10").compareTo(qualified.limit()));
        assertTrue(series.targets().stream().anyMatch(t -> t.parameter().equals("VOLATILE_ACIDITY")
            && t.warnMax().compareTo(new BigDecimal("0.6")) == 0), "the seeded acidity range travels with the series");

        var windowed = tracking.series(List.of("C-T1"), List.of("VOLATILE_ACIDITY"), now.minus(5, ChronoUnit.DAYS), null, false);
        assertEquals(1, windowed.points().size());
    }

    @Test
    void seriesRejectsEmptyOrOversizedSelections() {
        assertThrows(BusinessRuleException.class, () -> tracking.series(List.of(), List.of("PH"), null, null, false));
        assertThrows(BusinessRuleException.class, () -> tracking.series(List.of("C-T1"), List.of(), null, null, false));
        List<String> many = java.util.stream.IntStream.range(0, 41).mapToObj(i -> "C-" + i).toList();
        assertThrows(BusinessRuleException.class, () -> tracking.series(many, List.of("PH"), null, null, false));
    }

    @Test
    void seriesCanFollowTheWineBackToItsAncestors() {
        UUID category = jdbc.queryForObject("select id from internal_category where code = 'RED'", UUID.class);
        UUID color = UUID.randomUUID();
        UUID destination = jdbc.queryForObject("select id from destination limit 1", UUID.class);
        UUID child = content("T3", category, color, destination);
        UUID movement = movement("M-ANC", "TRANSFER_FULL", content1, content2);
        jdbc.update("insert into content_unit_lineage(id, content_unit_id, parent_content_unit_id, movement_id, contributed_liters) "
            + "values (?, ?, ?, ?, 1000)", UUID.randomUUID(), child, content1, movement);

        var without = tracking.series(List.of("C-T3"), List.of("VOLATILE_ACIDITY"), null, null, false);
        assertEquals(1, without.contents().size());
        assertTrue(without.points().isEmpty());

        var with = tracking.series(List.of("C-T3"), List.of("VOLATILE_ACIDITY"), null, null, true);
        var ancestor = with.contents().stream().filter(c -> c.code().equals("C-T1")).findFirst().orElseThrow();
        assertTrue(ancestor.ancestor());
        assertEquals("C-T3", ancestor.descendantCode());
        assertEquals(2, with.points().stream().filter(p -> p.content().equals("C-T1")).count());
    }

    @Test
    void eventsCombineMovementsAndStateReviews() {
        movement("M-1", "TRANSFER_FULL", content1, content2);
        jdbc.update("insert into fermentation_state_review(id, content_unit_id, process, previous_status, decision, reason, reviewed_by_id, reviewed_at) "
                + "values (?, ?, 'ALCOHOLIC'::fermentation_process, 'En curso', 'Terminada', 'Densidad estable', ?, ?)",
            UUID.randomUUID(), content1, admin, Timestamp.from(now.minus(1, ChronoUnit.DAYS)));

        var events = tracking.events(List.of("C-T1", "C-T2"), null, null);

        assertEquals(java.util.Set.of("TRANSFER", "STATE_REVIEW"),
            events.stream().filter(e -> e.content().equals("C-T1")).map(e -> e.type()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(events.stream().anyMatch(e -> e.content().equals("C-T2") && e.label().startsWith("Trasiego")),
            "the destination content gets the transfer too");
        assertTrue(events.get(0).at().isBefore(events.get(events.size() - 1).at()) || events.size() == 1, "sorted by time");
    }

    @Test
    void overviewRanksTheMostWorryingTankFirstAndShowsStatusTrendAndAge() {
        var overview = tracking.overview(List.of("VOLATILE_ACIDITY", "PH"));

        OverviewRow t1 = overview.rows().stream().filter(r -> r.content().equals("C-T1")).findFirst().orElseThrow();
        assertEquals("WARN", t1.worstStatus());                                  // 0.72 > 0.60 warning
        var acidity = t1.cells().stream().filter(c -> c.parameter().equals("VOLATILE_ACIDITY")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.72").compareTo(acidity.latest().value()));
        assertEquals(0, new BigDecimal("0.30").compareTo(acidity.previous().value()));
        assertEquals("UP", acidity.trend());
        assertEquals("WARN", acidity.latest().status());
        assertEquals(0L, acidity.latest().daysAgo());
        assertEquals("OK", t1.cells().stream().filter(c -> c.parameter().equals("PH")).findFirst().orElseThrow().latest().status());
        assertEquals(0, t1.openSamples(), "validated and invalidated analyses are not open");

        OverviewRow t2 = overview.rows().stream().filter(r -> r.content().equals("C-T2")).findFirst().orElseThrow();
        assertEquals(1, t2.openSamples());                                       // pending validation
        assertEquals("OK", t2.cells().get(0).latest().status());                 // "< 0.10" is safely under the 0.60 ceiling
        assertNull(t2.cells().get(1).latest());                                  // no pH yet
        assertNotNull(t2.daysSinceLastSample());

        int t1Position = overview.rows().indexOf(t1);
        assertTrue(t1Position < overview.rows().indexOf(t2), "warning first");
    }

    @Test
    void latestReturnsEveryParameterWithVolumeAndCapacity() {
        var latest = tracking.latest(List.of("C-T1"));

        assertEquals(1, latest.size());
        var content = latest.getFirst();
        assertEquals("D-T1", content.deposit());
        assertEquals(0, new BigDecimal("1000").compareTo(content.volumeLiters()));
        assertEquals(0, new BigDecimal("10000").compareTo(content.depositUsefulCapacityLiters()));
        var acidity = content.readings().stream().filter(r -> r.parameter().equals("VOLATILE_ACIDITY")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.72").compareTo(acidity.value()));      // the newest, not 0.30
        assertEquals(0L, acidity.daysAgo());
        assertTrue(content.readings().stream().anyMatch(r -> r.parameter().equals("PH")));
        assertTrue(content.targets().stream().anyMatch(t -> t.parameter().equals("VOLATILE_ACIDITY") && t.warnMax().compareTo(new BigDecimal("0.6")) == 0),
            "the acidity range that applies to this content comes with it");
        assertThrows(BusinessRuleException.class, () -> tracking.latest(List.of()));
    }

    @Test
    void contentsOutsideTheReadableZonesAreInvisible() {
        assertEquals(2, tracking.series(List.of("C-T1", "C-T2"), List.of("PH"), null, null, false).contents().size());
        actAs("operario");   // CELLAR_OPERATOR limited to zone NAVE-A: does not cover Z-TRK
        assertTrue(tracking.series(List.of("C-T1", "C-T2"), List.of("PH"), null, null, false).contents().isEmpty());
        assertTrue(tracking.events(List.of("C-T1"), null, null).isEmpty());
        assertTrue(tracking.overview(List.of("PH")).rows().stream().noneMatch(r -> r.content().startsWith("C-T")));
    }

    @Test
    void targetsResolveMostSpecificAndEvaluateQualifiedResults() {
        Target global = new Target("PH", null, null, null, null, new BigDecimal("3.8"), null, new BigDecimal("4.0"));
        Target red = new Target("PH", "RED", null, null, null, new BigDecimal("3.7"), null, new BigDecimal("3.9"));
        Target redEnd = new Target("PH", "RED", "Terminada", null, null, new BigDecimal("3.6"), null, new BigDecimal("3.9"));
        var all = List.of(global, red, redEnd);
        assertEquals(redEnd, ParameterTargetService.resolve(all, "PH", "RED", "terminada", null));
        assertEquals(red, ParameterTargetService.resolve(all, "PH", "RED", "En curso", null));
        assertEquals(global, ParameterTargetService.resolve(all, "PH", "WHITE", null, null));
        assertNull(ParameterTargetService.resolve(all, "DENSITY", "RED", null, null));

        assertEquals("OK", ParameterTargetService.evaluate(new BigDecimal("3.5"), "NONE", null, global));
        assertEquals("WARN", ParameterTargetService.evaluate(new BigDecimal("3.9"), "NONE", null, global));
        assertEquals("CRIT", ParameterTargetService.evaluate(new BigDecimal("4.2"), "NONE", null, global));
        assertEquals("NONE", ParameterTargetService.evaluate(new BigDecimal("3.5"), "NONE", null, null));
        Target sulfur = new Target("FREE_SO2", null, null, null, new BigDecimal("15"), null, new BigDecimal("8"), null);
        assertEquals("CRIT", ParameterTargetService.evaluate(null, "LESS_THAN", new BigDecimal("5"), sulfur));
        assertEquals("UNKNOWN", ParameterTargetService.evaluate(null, "LESS_THAN", new BigDecimal("20"), sulfur));
        assertEquals("NONE", ParameterTargetService.evaluate(null, "NOT_MEASURED", null, sulfur));
    }

    @Test
    void targetsAreEditableAndValidated() {
        var created = targets.create(new TargetRequest("DENSITY", "RED", "En curso", null, null, new BigDecimal("1.02"), null,
            new BigDecimal("1.10"), "prueba"));
        assertEquals("Densidad", created.parameterName());
        assertThrows(ConflictException.class, () -> targets.create(new TargetRequest("DENSITY", "RED", "En curso", null, null,
            new BigDecimal("1.01"), null, null, null)));
        assertThrows(BusinessRuleException.class, () -> targets.create(new TargetRequest("DENSITY", null, null, null, null,
            new BigDecimal("1.20"), null, new BigDecimal("1.10"), null)));   // warn above crit
        assertThrows(BusinessRuleException.class, () -> targets.create(new TargetRequest("DENSITY", null, "Nada", null, null,
            null, null, null, null)));                                       // no limit at all
        var updated = targets.update(created.id(), new TargetRequest("DENSITY", "RED", "En curso", null, null,
            new BigDecimal("1.05"), null, new BigDecimal("1.10"), null));
        assertEquals(0, new BigDecimal("1.05").compareTo(updated.warnMax()));
        targets.delete(created.id());
        assertFalse(targets.list().stream().anyMatch(t -> t.id().equals(created.id())));
        assertEquals(3, jdbc.queryForObject("select count(*) from audit_log where entity_id = ?", Integer.class, created.id()));
    }

    // ---------------------------------------------------------------- helpers

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private UUID content(String suffix, UUID category, UUID color, UUID destination) {
        UUID deposit = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        jdbc.update("insert into deposit(id, code, center_id, zone_id, useful_capacity_liters) values (?, ?, ?, ?, 10000)",
            deposit, "D-" + suffix, CENTER, zone);
        jdbc.update("insert into lot(id, code, campaign, category_id, destination_id, entry_date, responsible_id, center_id) "
            + "values (?, ?, 2026, ?, ?, current_date, ?, ?)", lot, "L-" + suffix, category, destination, admin, CENTER);
        jdbc.update("insert into content_unit(id, code, lot_id, category_id, volume_liters) values (?, ?, ?, ?, 1000)",
            content, "C-" + suffix, lot, category);
        jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, 1000)",
            UUID.randomUUID(), content, deposit, Timestamp.from(now.minus(20, ChronoUnit.DAYS)));
        return content;
    }

    private UUID sample(String code, UUID content, Instant takenAt, String analysisStatus) {
        UUID sample = UUID.randomUUID();
        jdbc.update("insert into sample(id, code, content_unit_id, occupation_id, deposit_id_at_sampling, taken_at, taken_by_id) "
                + "select ?, ?, ?, o.id, o.deposit_id, ?, ? from occupation o where o.content_unit_id = ?",
            sample, code, content, Timestamp.from(takenAt), admin, content);
        jdbc.update("insert into analysis(id, sample_id, status) values (?, ?, ?::analysis_status)", UUID.randomUUID(), sample,
            analysisStatus);
        return sample;
    }

    private void result(UUID sample, String parameter, String value, String qualifier, String limit, boolean current) {
        jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, qualifier_limit, is_current, created_by_id) "
                + "select ?, a.id, p.id, ?::result_qualifier, ?::numeric, ?::numeric, ?, ? from analysis a, parameter p "
                + "where a.sample_id = ? and p.code = ?", UUID.randomUUID(), qualifier, value, limit, current, admin, sample, parameter);
    }

    /** An executed transfer from one content to another (deposit of each = the one it occupies). */
    private UUID movement(String code, String type, UUID source, UUID destination) {
        UUID movement = UUID.randomUUID();
        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason) "
                + "values (?, ?, ?::movement_type, 'EXECUTED'::movement_status, ?, ?, 'Trasiego de prueba')",
            movement, code, type, Timestamp.from(now.minus(5, ChronoUnit.DAYS)), admin);
        jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, "
                + "destination_content_unit_id, destination_deposit_id, volume_liters) values (?, ?, ?, "
                + "(select deposit_id from occupation where content_unit_id = ?), ?, (select deposit_id from occupation where content_unit_id = ?), 1000)",
            UUID.randomUUID(), movement, source, source, destination, destination);
        return movement;
    }
}
