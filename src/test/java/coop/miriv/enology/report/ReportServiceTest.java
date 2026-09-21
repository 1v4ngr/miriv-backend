package coop.miriv.enology.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.report.dto.ReportDto.CreateReportRequest;
import coop.miriv.enology.report.dto.ReportDto.Filters;
import coop.miriv.enology.report.dto.ReportDto.JobView;
import coop.miriv.enology.report.dto.ReportDto.PhaseRequest;
import coop.miriv.enology.report.model.CellarReport;
import coop.miriv.enology.report.model.CellarReport.AnalyticRow;
import coop.miriv.enology.report.model.CellarReport.Block;
import coop.miriv.enology.report.model.CellarReport.DepositReport;
import coop.miriv.enology.report.service.CellarReportBuilder;
import coop.miriv.enology.report.service.ReportPhaseService;
import coop.miriv.enology.report.service.ReportService;
import coop.miriv.enology.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
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

/** Cellar-status report: phase timeline, phase-scoped charts, stored PDF / .xlsx and immutability. */
@Transactional
class ReportServiceTest extends IntegrationTest {

    private static final UUID CENTER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired ReportService reports;
    @Autowired CellarReportBuilder builder;
    @Autowired ReportPhaseService phases;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();
    private UUID admin;
    private UUID content;
    private String deposit;

    @BeforeEach
    void setUp() {
        actAs("admin");
        admin = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID zone = UUID.randomUUID();
        jdbc.update("insert into zone(id, center_id, code, name) values (?, ?, ?, 'Zona informes')", zone, CENTER, "Z-" + suffix);
        UUID must = jdbc.queryForObject("select id from internal_category where code = 'MUST'", UUID.class);
        UUID depositId = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        content = UUID.randomUUID();
        deposit = "R-" + suffix;
        jdbc.update("insert into deposit(id, code, center_id, zone_id, useful_capacity_liters) values (?, ?, ?, ?, 10000)",
            depositId, deposit, CENTER, zone);
        jdbc.update("insert into lot(id, code, campaign, category_id, entry_date, responsible_id, center_id) "
            + "values (?, ?, 2026, ?, current_date, ?, ?)", lot, "L-" + suffix, must, admin, CENTER);
        jdbc.update("insert into content_unit(id, code, lot_id, category_id, volume_liters) values (?, ?, ?, ?, 5000)",
            content, "C-" + suffix, lot, must);
        jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, 5000)",
            UUID.randomUUID(), content, depositId, Timestamp.from(now.minus(20, ChronoUnit.DAYS)));

        // Fermenting must: density falls.
        result(sample("S1-" + suffix, now.minus(10, ChronoUnit.DAYS)), "DENSITY", "1.0800", true);
        result(sample("S2-" + suffix, now.minus(7, ChronoUnit.DAYS)), "DENSITY", "1.0100", true);
        // Five days ago the alcoholic fermentation was closed and the must became a red wine.
        Instant closed = now.minus(5, ChronoUnit.DAYS);
        jdbc.update("insert into fermentation_state_review(id, content_unit_id, process, previous_status, decision, reason, "
                + "reviewed_by_id, reviewed_at) values (?, ?, 'ALCOHOLIC', null, 'FINISHED', 'Densidad estable', ?, ?)",
            UUID.randomUUID(), content, admin, Timestamp.from(closed));
        jdbc.update("insert into fermentation_state(id, content_unit_id, process, estimated_status, confirmed_status, confirmed_at) "
            + "values (?, ?, 'ALCOHOLIC', 'NOT_EVALUABLE', 'FINISHED', ?)", UUID.randomUUID(), content, Timestamp.from(closed));
        jdbc.update("update content_unit set category_id = (select id from internal_category where code = 'RED') where id = ?", content);
        jdbc.update("insert into audit_log(id, entity_name, entity_id, action, previous_value, new_value, author_id, reason, created_at) "
                + "values (?, 'content_unit', ?, 'CONTENT_RECLASSIFIED', '{\"category\":\"Mosto\"}', '{\"category\":\"Tinto\"}', ?, 'Fin FA', ?)",
            UUID.randomUUID(), content, admin, Timestamp.from(closed));
        // Wine: sulphur, one of them still provisional.
        result(sample("S3-" + suffix, now.minus(2, ChronoUnit.DAYS)), "FREE_SO2", "28", true);
        result(sample("S4-" + suffix, now.minus(1, ChronoUnit.DAYS)), "FREE_SO2", "12", false);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void theContentPassesFromAlcoholicFermentationToWineAndEachPhaseChartsItsOwnParameters() {
        CellarReport report = builder.build("INF-T", "Prueba", scope(), true);
        DepositReport d = report.deposits().stream().filter(item -> item.deposit().equals(deposit)).findFirst().orElseThrow();

        assertEquals("WINE", d.phase().code());
        assertEquals(List.of("ALCOHOLIC", "WINE"), d.segments().stream().map(s -> s.phase().code()).toList());
        assertEquals("Mosto", d.segments().getFirst().categoryName());
        assertEquals("Tinto", d.segments().getLast().categoryName());

        // Density is charted in the fermentation stretch, sulphur in the wine stretch.
        assertEquals(List.of("DENSITY"), d.blocks().getFirst().series().stream().map(s -> s.parameter().code()).toList());
        assertEquals(2, d.blocks().getFirst().series().getFirst().points().size());
        assertEquals(List.of("FREE_SO2"), d.blocks().getLast().series().stream().map(s -> s.parameter().code()).toList());

        // Each result carries the phase it was taken in, and the wine targets apply to the wine samples.
        List<AnalyticRow> rows = report.rows().stream().filter(row -> row.content().equals(d.content())).toList();
        assertTrue(rows.stream().filter(row -> row.parameter().code().equals("DENSITY")).allMatch(row -> row.phase().code().equals("ALCOHOLIC")));
        AnalyticRow low = rows.stream().filter(row -> row.value() != null && row.value().intValue() == 12).findFirst().orElseThrow();
        assertEquals("WINE", low.phase().code());
        assertEquals("WARN", low.status(), "12 mg/L of free SO2 is below the 15 mg/L warning of V29");
        assertFalse(low.validated());
    }

    @Test
    void everyAnalysedParameterGetsAChartEvenWhenThePhaseDoesNotListIt() {
        // Glycerol is not among the alcoholic-fermentation phase parameters (V51).
        result(sample("S5-" + UUID.randomUUID().toString().substring(0, 6), Instant.now().minus(8, ChronoUnit.DAYS)), "GLYCEROL", "6.5", true);
        CellarReport report = builder.build("INF-T", "Prueba", scope(), true);
        DepositReport d = report.deposits().stream().filter(item -> item.deposit().equals(deposit)).findFirst().orElseThrow();

        Block fermentation = d.blocks().getFirst();
        assertEquals(List.of("DENSITY", "GLYCEROL"), fermentation.series().stream().map(s -> s.parameter().code()).toList());
        // The table keeps the phase's own columns.
        assertEquals(List.of("DENSITY"), fermentation.columns().stream().map(p -> p.code()).toList());
    }

    @Test
    void provisionalResultsAreLeftOutUnlessAskedFor() {
        CellarReport report = builder.build("INF-T", "Prueba", scope(), false);
        assertTrue(report.rows().stream().allMatch(AnalyticRow::validated));
        assertEquals(3, report.rows().stream().filter(row -> row.deposit().equals(deposit)).count());
    }

    @Test
    void anIssuedReportStoresBothFilesAndNeverChanges() {
        JobView first = reports.create(new CreateReportRequest("CELLAR_STATUS", null, scope(), true));
        assertEquals("AVAILABLE", first.status(), first.error());
        assertTrue(first.pdf() && first.xlsx());
        assertEquals(4, first.recordCount());
        byte[] pdf = reports.file(first.code(), "pdf").content();
        byte[] xlsx = reports.file(first.code(), "xlsx").content();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("PK", new String(xlsx, 0, 2, StandardCharsets.US_ASCII));

        // Correcting data later issues a new report; the first one keeps its files.
        jdbc.update("update result set numeric_value = 1.0500 where numeric_value = 1.0100 and analysis_id in "
            + "(select a.id from analysis a join sample s on s.id = a.sample_id where s.content_unit_id = ?)", content);
        JobView second = reports.create(new CreateReportRequest("CELLAR_STATUS", null, scope(), true));
        assertFalse(first.code().equals(second.code()));
        assertArrayEquals(xlsx, reports.file(first.code(), "xlsx").content());
        assertEquals(2, reports.list(0, 50).items().stream().filter(job -> job.code().equals(first.code())
            || job.code().equals(second.code())).count());
    }

    @Test
    void unsupportedTypesAndBackwardsRangesAreRejectedBeforeAnythingIsStored() {
        assertThrows(BusinessRuleException.class, () -> reports.create(new CreateReportRequest("TRACEABILITY", null, scope(), false)));
        Filters backwards = new Filters(List.of(), List.of(deposit), List.of(), List.of(), Filters.RANGE,
            java.time.LocalDate.now().plusDays(1), java.time.LocalDate.now());
        assertThrows(BusinessRuleException.class, () -> reports.create(new CreateReportRequest("CELLAR_STATUS", null, backwards, false)));
    }

    @Test
    void phasesAreEditableAndValidated() {
        var created = phases.create(new PhaseRequest(null, "Crianza en barrica", null, "#445566", true, List.of("RED"),
            List.of("FINISHED"), List.of("FINISHED", "NONE"), List.of("FREE_SO2", "VOLATILE_ACIDITY")));
        assertEquals("CRIANZA_EN_BARRICA", created.code());
        assertThrows(BusinessRuleException.class, () -> phases.create(new PhaseRequest(null, "Mala", null, "rojo", true,
            List.of(), List.of(), List.of(), List.of("PH"))));
        assertThrows(BusinessRuleException.class, () -> phases.create(new PhaseRequest(null, "Mala", null, null, true,
            List.of(), List.of("NOT_EXPECTED"), List.of(), List.of("PH"))));     // not an alcoholic state
        assertThrows(BusinessRuleException.class, () -> phases.create(new PhaseRequest(null, "Mala", null, null, true,
            List.of(), List.of(), List.of(), List.of())));                     // nothing to chart

        // Order decides: moved first, the new phase wins over the generic wine phase for a finished red.
        List<UUID> order = new java.util.ArrayList<>(phases.list().stream().map(p -> p.id()).toList());
        order.remove(created.id());
        order.addFirst(created.id());
        phases.reorder(order);
        assertEquals("CRIANZA_EN_BARRICA", ReportPhaseService.resolve(phases.all(), "RED", "FINISHED", null).code());
        assertEquals("ALCOHOLIC", ReportPhaseService.resolve(phases.all(), "MUST", null, null).code());
        phases.delete(created.id());
        assertEquals("WINE", ReportPhaseService.resolve(phases.all(), "RED", "FINISHED", null).code());
        assertNull(ReportPhaseService.resolve(List.of(), "RED", null, null));
    }

    // ---------------------------------------------------------------- helpers

    private Filters scope() {
        return new Filters(List.of(), List.of(deposit), List.of(), List.of(), Filters.CONTENT_START, null, null);
    }

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private UUID sample(String code, Instant takenAt) {
        UUID sample = UUID.randomUUID();
        jdbc.update("insert into sample(id, code, content_unit_id, occupation_id, deposit_id_at_sampling, taken_at, taken_by_id) "
                + "select ?, ?, ?, o.id, o.deposit_id, ?, ? from occupation o where o.content_unit_id = ?",
            sample, code, content, Timestamp.from(takenAt), admin, content);
        jdbc.update("insert into analysis(id, sample_id, status) values (?, ?, 'VALIDATED'::analysis_status)", UUID.randomUUID(), sample);
        return sample;
    }

    private void result(UUID sample, String parameter, String value, boolean validated) {
        jdbc.update("insert into result(id, analysis_id, parameter_id, numeric_value, validated, created_by_id) "
                + "select ?, a.id, p.id, ?::numeric, ?, ? from analysis a, parameter p where a.sample_id = ? and p.code = ?",
            UUID.randomUUID(), value, validated, admin, sample, parameter);
    }
}
