package coop.miriv.enology;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.CompleteCleaningRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.StateReviewRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.DepositCleaningService;
import coop.miriv.enology.cellar.service.ContentService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.cellar.service.MovementService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.dashboard.service.WorkHomeService;
import coop.miriv.enology.identity.repository.AppUserRepository;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.incident.dto.ResolveIncidentRequest;
import coop.miriv.enology.incident.service.IncidentService;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.CorrectionRequest;
import coop.miriv.enology.laboratory.dto.ResultInput;
import coop.miriv.enology.laboratory.dto.ResultsRequest;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.plan.dto.CreatePlanRequest;
import coop.miriv.enology.plan.dto.PlanVersionRequest;
import coop.miriv.enology.plan.service.PlanService;
import coop.miriv.enology.task.dto.CompleteTaskRequest;
import coop.miriv.enology.task.dto.CreateTaskRequest;
import coop.miriv.enology.task.service.TaskService;
import coop.miriv.enology.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Transactional
class CellarLaboratoryIntegrationTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired AppUserRepository users;
    @Autowired AppUserDetailsService userDetailsService;
    @Autowired DepositService deposits;
    @Autowired DepositCleaningService cleaning;
    @Autowired LotService lots;
    @Autowired MovementService movements;
    @Autowired ContentService contents;
    @Autowired LaboratoryService laboratory;
    @Autowired WorkHomeService workHome;
    @Autowired PlanService plans;
    @Autowired TaskService tasks;
    @Autowired IncidentService incidents;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void authenticate() {
        // F1C-04: build the principal through the real detail service so it carries the actual
        // permission/zone scope from the seed, not an "everything allowed" stand-in.
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void initialEntryTransferAndHistoricalSampleStayConsistent() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String source = "T-S-" + suffix;
        String target = "T-D-" + suffix;
        String lotCode = "T-L-" + suffix;
        deposits.create(new DepositRequest(source, "CENTRO-NORTE", "NAVE-A", "1", new BigDecimal("2000"), "Steel", false));
        deposits.create(new DepositRequest(target, "CENTRO-NORTE", "NAVE-A", "2", new BigDecimal("1000"), "Steel", false));
        lots.create(new CreateLotRequest(new LotRequest(lotCode, LocalDate.now(TIMEZONE).getYear(),
            "Tinto", "Vino tranquilo", "enologo", LocalDate.now(TIMEZONE), "Reception 123", List.of("Tempranillo")),
            new LotEntryRequest(source, new BigDecimal("1000"), LocalDate.now(TIMEZONE))));
        LocalDateTime movementTime = LocalDateTime.now(TIMEZONE).minusHours(1);
        var movement = movements.register(new MovementRequest("Trasiego", movementTime.toLocalDate(),
            movementTime.toLocalTime().withNano(0), "enologo", "Routine transfer", source, target,
            new BigDecimal("300"), new BigDecimal("10"), "test-" + suffix, false, null, null, false));
        assertEquals(0, movement.sourceFinalLiters().compareTo(new BigDecimal("690")));
        assertEquals(0, movement.destinationFinalLiters().compareTo(new BigDecimal("300")));
        var transferred = deposits.get(target);
        assertEquals(movement.destinationContentCode(), transferred.occupations().getFirst().contentCode());
        assertTrue(lots.genealogy(lotCode).size() >= 2);
        assertEquals(lotCode, contents.get(movement.destinationContentCode()).lot().code());
        var firstVersion = new PlanVersionRequest("Controlled fermentation", "Yeast A", null,
            new BigDecimal("4.0"), "NOT_DESIRED", null, null, null,
            new BigDecimal("17"), new BigDecimal("22"), "CONTROL", 2, "Initial plan", Instant.now());
        assertEquals(1, plans.create(movement.destinationContentCode(),
            new CreatePlanRequest("Red wine plan", "Vino tranquilo", firstVersion)).currentVersion());
        var secondVersion = new PlanVersionRequest("Controlled fermentation", "Yeast A", null,
            new BigDecimal("3.0"), "NOT_DESIRED", null, null, null,
            new BigDecimal("18"), new BigDecimal("21"), "CONTROL", 1, "Adjust sampling", Instant.now());
        assertEquals(2, plans.addVersion(movement.destinationContentCode(), secondVersion).currentVersion());
        assertEquals(2, plans.get(movement.destinationContentCode()).versions().size());
        contents.review(movement.destinationContentCode(),
            new StateReviewRequest("alcoholic", "Activa", "Reviewed by enologist"));
        assertEquals("Activa", contents.get(movement.destinationContentCode()).alcoholic().confirmation());
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusMinutes(30).withNano(0);
        String sampleCode = "T-M-" + suffix;
        var sample = laboratory.create(new NewSampleRequest(sampleCode, target,
            movement.destinationContentCode(), lotCode, "Tinto", takenAt, takenAt.toLocalDate(),
            "Control", "enologo", null, null));
        assertEquals(movement.destinationContentCode(), sample.contentCode());
        var updated = laboratory.saveResults(sampleCode, new ResultsRequest(
            List.of(new ResultInput("pH", "3,42", "", null, null)), "Borrador",
            LocalDate.now(TIMEZONE), "Internal laboratory", "Meter", "Electrode", null));
        assertEquals(1, updated.completed());
        assertEquals(10, updated.panelParameters().size()); // 8 + MALIC_ACID and TOTAL_ACIDITY_TH2 (V27)
        assertThrows(BusinessRuleException.class, () -> laboratory.validate(sampleCode, "Reviewed"));
        var complete = laboratory.saveResults(sampleCode, new ResultsRequest(List.of(
            new ResultInput("pH", "3,42", "", null, null),
            new ResultInput("Densidad", "0,996", "g/mL", null, null),
            new ResultInput("Acidez volátil", "0,72", "g/L", null, null),
            new ResultInput("Azúcares reductores", "4,8", "g/L", null, null),
            new ResultInput("Etanol", "12,4", "% vol.", null, null),
            new ResultInput("Glucosa más fructosa", "3,1", "g/L", null, null),
            new ResultInput("Acidez total", "5,4", "g/L como tartárico", null, null),
            new ResultInput("CO2 disuelto", "850", "mg/L", null, null),
            // "Ácido málico" is also a legacy alias of L_MALIC_ACID: the Control panel's MALIC_ACID must win.
            new ResultInput("Ácido málico", "1,81", "g/L", null, null),
            new ResultInput("Acidez total TH2", "6,54", "g/L como tartárico", null, null)),
            "Pendiente validar", LocalDate.now(TIMEZONE), "Internal laboratory", "Meter", "Electrode", null));
        assertEquals("Pendiente validar", complete.status());
        assertEquals("Validado", laboratory.validate(sampleCode, "Reviewed").status());
        var task = tasks.create(new CreateTaskRequest("Check laboratory trend", target,
            movement.destinationContentCode(), "enologo", Instant.now().plusSeconds(86400),
            "MEDIUM", "ANALYSIS_REQUIRED", null));
        assertEquals("IN_PROGRESS", tasks.start(task.code()).status());
        assertEquals("DONE", tasks.complete(task.code(),
            new CompleteTaskRequest("Reviewed", "No action required", sampleCode, null, null)).status());
        String incidentCode = "INC-" + suffix;
        jdbc.update("insert into incident(id, code, deposit_id, priority, title) "
                + "values (?, ?, ?, 'HIGH'::alert_priority, ?)", UUID.randomUUID(), incidentCode,
            transferred.id(), "Manual validation required");
        assertEquals("IN_REVIEW", incidents.acknowledge(incidentCode).status());
        assertEquals("ASSIGNED", incidents.assign(incidentCode, "enologo").status());
        assertTrue(incidents.silence(incidentCode, Instant.now().plusSeconds(3600), "Investigating")
            .silencedUntil().isAfter(Instant.now()));
        assertEquals("RESOLVED", incidents.close(incidentCode,
            new ResolveIncidentRequest("Measurement reviewed", null), false).status());
        LocalDateTime exitTime = LocalDateTime.now(TIMEZONE).minusMinutes(20);
        movements.register(new MovementRequest("Salida", exitTime.toLocalDate(),
            exitTime.toLocalTime().withNano(0), "enologo", "Final dispatch", source, null,
            new BigDecimal("690"), BigDecimal.ZERO, "exit-" + suffix, false, null, null, false));
        cleaning.start(source);
        cleaning.complete(source, new CompleteCleaningRequest("Wash and inspect", "Passed", "No residue", true));
        var corrected = laboratory.correct(sampleCode, "pH", new CorrectionRequest("3,40", "Instrument calibration"));
        assertEquals("Pendiente validar", corrected.status());
        assertTrue(corrected.results().stream().filter(result -> result.parameter().equals("pH"))
            .findFirst().orElseThrow().versions().size() >= 2);
        assertEquals("Invalidado", laboratory.invalidate(sampleCode, "Contaminated sample").status());
        assertEquals("Centro Norte", workHome.get().center());
    }
}
