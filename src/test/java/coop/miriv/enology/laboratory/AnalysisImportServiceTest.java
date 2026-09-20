package coop.miriv.enology.laboratory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportResponse;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRow;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRowResult;
import coop.miriv.enology.laboratory.service.AnalysisImportService;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.tracking.service.TrackingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Pasting the analyser sheet: valid rows are imported, the rest are reported one by one. */
@Transactional
class AnalysisImportServiceTest extends coop.miriv.enology.support.IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired AnalysisImportService imports;
    @Autowired coop.miriv.enology.laboratory.service.ImportTemplateService importTemplates;
    @Autowired LaboratoryService laboratory;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired TrackingService tracking;
    @Autowired AppUserDetailsService userDetailsService;

    private String suffix;
    private String deposit;
    private String content;
    private LocalDateTime takenAt;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));

        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        deposit = "I-D-" + suffix;
        deposits.create(new DepositRequest(deposit, "CENTRO-NORTE", "NAVE-A", "3", new BigDecimal("2000"), "Steel", false));
        lots.create(new CreateLotRequest(new LotRequest("I-L-" + suffix, LocalDate.now(TIMEZONE).getYear(),
            "Tinto", "Vino tranquilo", "enologo", LocalDate.now(TIMEZONE).minusDays(3), "Reception", List.of("Tempranillo")),
            new LotEntryRequest(deposit, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(3))));
        content = deposits.get(deposit).occupations().getFirst().contentCode();
        takenAt = LocalDateTime.now(TIMEZONE).minusHours(2).withNano(0);
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void importsARowOfTheAnalyserSheet() {
        ImportResponse response = imports.execute(new ImportRequest(List.of(row(1, deposit, takenAt))));

        assertEquals(1, response.imported());
        assertEquals(0, response.skipped());
        ImportRowResult result = response.rows().getFirst();
        assertEquals(ImportRowResult.OK, result.status());
        assertEquals(content, result.contentCode());
        assertNotNull(result.sampleCode());

        var sample = laboratory.get(result.sampleCode());
        assertEquals("Validado", sample.status());
        assertEquals(deposit, sample.originDeposit());
        assertEquals(4, sample.results().size());

        // The values reach the curves of the right wine, temperature included.
        var series = tracking.series(List.of(content), List.of("PH", "DENSITY", "CONTENT_TEMPERATURE"), null, null, false);
        assertEquals(3, series.points().size());
        assertEquals(0, series.points().stream()
            .filter(point -> point.parameter().equals("CONTENT_TEMPERATURE"))
            .findFirst().orElseThrow().value().compareTo(new BigDecimal("12.5")));
    }

    @Test
    void reportsRowsThatCannotBeImportedWithoutBlockingTheRest() {
        ImportResponse response = imports.execute(new ImportRequest(List.of(
            row(1, "NO-EXISTE-" + suffix, takenAt),
            row(2, deposit, takenAt),
            row(3, deposit, LocalDateTime.now(TIMEZONE).minusDays(30)))));

        assertEquals(1, response.imported());
        assertEquals(2, response.skipped());
        assertTrue(message(response, 1).contains("no existe en el centro"), message(response, 1));
        assertEquals(ImportRowResult.OK, status(response, 2));
        assertTrue(message(response, 3).contains("no tenía contenido"), message(response, 3));
    }

    @Test
    void previewChecksTheSameRulesWithoutWriting() {
        ImportResponse preview = imports.preview(new ImportRequest(List.of(
            row(1, deposit, takenAt),
            new ImportRow(2, deposit, takenAt.minusHours(1), Map.of("PH", "no numérico"), null))));

        assertEquals(ImportRowResult.OK, status(preview, 1));
        assertNull(preview.rows().getFirst().sampleCode(), "preview must not create anything");
        assertTrue(message(preview, 2).contains("no numérico"), message(preview, 2));
        assertTrue(laboratory.listByContent(content).isEmpty(), "nothing was written");
    }

    @Test
    void secondImportOfTheSameReadingIsReportedAsDuplicate() {
        imports.execute(new ImportRequest(List.of(row(1, deposit, takenAt))));

        ImportResponse again = imports.execute(new ImportRequest(List.of(row(1, deposit, takenAt))));

        assertEquals(0, again.imported());
        assertEquals(ImportRowResult.DUPLICATE, status(again, 1));
    }

    @Test
    void analysisWithoutTemperatureCanStillBeValidated() {
        // Temperature is optional in the CONTROL panel (V45), so a manual sample without it validates.
        String sampleCode = "I-M-" + suffix;
        laboratory.create(new coop.miriv.enology.laboratory.dto.NewSampleRequest(sampleCode, deposit, content,
            null, null, takenAt, takenAt.toLocalDate(), "Control", "enologo", null, null));
        laboratory.saveResults(sampleCode, new coop.miriv.enology.laboratory.dto.ResultsRequest(List.of(
            new coop.miriv.enology.laboratory.dto.ResultInput("Acidez volátil", "0,44", "g/L", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("pH", "3,34", "", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Densidad", "1,05", "g/mL", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Azúcares reductores", "136,6", "g/L", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Ácido málico", "1,61", "g/L", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Acidez total TH2", "5,79", "g/L como tartárico", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Etanol", "5,27", "% vol.", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Glucosa más fructosa", "129", "g/L", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Acidez total", "3,78", "g/L como tartárico", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("CO2 disuelto", "8023", "mg/L", null, null)),
            "Pendiente validar", takenAt.toLocalDate(), "Internal laboratory", "Meter", "Electrode", null));

        assertEquals("Validado", laboratory.validate(sampleCode, "Sin temperatura").status());

        // And a second sample of the same panel accepts temperature when it is measured.
        String withTemperature = "I-T-" + suffix;
        LocalDateTime later = takenAt.plusMinutes(30);
        laboratory.create(new coop.miriv.enology.laboratory.dto.NewSampleRequest(withTemperature, deposit, content,
            null, null, later, later.toLocalDate(), "Control", "enologo", null, null));
        var saved = laboratory.saveResults(withTemperature, new coop.miriv.enology.laboratory.dto.ResultsRequest(List.of(
            new coop.miriv.enology.laboratory.dto.ResultInput("pH", "3,34", "", null, null),
            new coop.miriv.enology.laboratory.dto.ResultInput("Temperatura del contenido", "12,5", "°C", null, null)),
            "Borrador", later.toLocalDate(), "Internal laboratory", "Meter", "Probe", null));
        assertTrue(saved.results().stream().anyMatch(item -> item.parameter().contains("Temperatura")),
            saved.results().toString());
    }

    @Test
    void savesAndOverwritesTheColumnMatchingOfTheCentre() {
        var columns = List.of(new coop.miriv.enology.laboratory.dto.ImportDto.TemplateColumn("ID", "deposit"),
            new coop.miriv.enology.laboratory.dto.ImportDto.TemplateColumn("TEMPERATURA", "param:CONTENT_TEMPERATURE"));
        var saved = importTemplates.save(new coop.miriv.enology.laboratory.dto.ImportDto.TemplateRequest(
            "Hoja del analizador " + suffix, columns));

        assertEquals(2, saved.columns().size());
        assertEquals("param:CONTENT_TEMPERATURE", saved.columns().get(1).target());
        assertTrue(importTemplates.list().stream().anyMatch(item -> item.name().equals(saved.name())));

        // Saving under the same name corrects the template instead of duplicating it.
        var corrected = importTemplates.save(new coop.miriv.enology.laboratory.dto.ImportDto.TemplateRequest(
            saved.name(), List.of(new coop.miriv.enology.laboratory.dto.ImportDto.TemplateColumn("ID", "deposit"))));
        assertEquals(1, corrected.columns().size());
        assertEquals(1, importTemplates.list().stream().filter(item -> item.name().equals(saved.name())).count());

        importTemplates.delete(saved.name());
        assertTrue(importTemplates.list().stream().noneMatch(item -> item.name().equals(saved.name())));
    }

    private ImportRow row(int reference, String depositCode, LocalDateTime when) {
        return new ImportRow(reference, depositCode, when,
            Map.of("PH", "3,34", "DENSITY", "1,05", "CONTENT_TEMPERATURE", "12,5ºC", "ETHANOL", "5,27"), null);
    }

    private static String status(ImportResponse response, int reference) {
        return find(response, reference).status();
    }

    private static String message(ImportResponse response, int reference) {
        return String.valueOf(find(response, reference).message());
    }

    private static ImportRowResult find(ImportResponse response, int reference) {
        return response.rows().stream().filter(row -> row.reference() == reference).findFirst().orElseThrow();
    }
}
