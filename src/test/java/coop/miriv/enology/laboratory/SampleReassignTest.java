package coop.miriv.enology.laboratory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.ResultInput;
import coop.miriv.enology.laboratory.dto.ResultsRequest;
import coop.miriv.enology.laboratory.dto.SampleResponse;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.support.IntegrationTest;
import coop.miriv.enology.tracking.service.TrackingService;
import java.math.BigDecimal;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** A sample registered against the wrong tank can be moved, and its results follow the right wine. */
@Transactional
class SampleReassignTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired LaboratoryService laboratory;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired TrackingService tracking;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserDetailsService userDetailsService;

    private String suffix;
    private String wrongDeposit;
    private String rightDeposit;
    private String wrongContent;
    private String rightContent;
    private String sampleCode;

    @BeforeEach
    void setUp() {
        authenticateAs("enologo");
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        wrongDeposit = "R-W-" + suffix;
        rightDeposit = "R-R-" + suffix;
        LocalDate entryDate = LocalDate.now(TIMEZONE).minusDays(5);
        deposits.create(new DepositRequest(wrongDeposit, "CENTRO-NORTE", "NAVE-A", "5", new BigDecimal("1000"), "Steel", false));
        deposits.create(new DepositRequest(rightDeposit, "CENTRO-NORTE", "NAVE-A", "6", new BigDecimal("1000"), "Steel", false));
        lots.create(new CreateLotRequest(lot("R-LW-" + suffix), new LotEntryRequest(wrongDeposit, new BigDecimal("500"), entryDate)));
        lots.create(new CreateLotRequest(lot("R-LR-" + suffix), new LotEntryRequest(rightDeposit, new BigDecimal("500"), entryDate)));
        wrongContent = deposits.get(wrongDeposit).occupations().getFirst().contentCode();
        rightContent = deposits.get(rightDeposit).occupations().getFirst().contentCode();

        sampleCode = "R-M-" + suffix;
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusDays(1).withNano(0);
        laboratory.create(new NewSampleRequest(sampleCode, wrongDeposit, wrongContent, null, null, takenAt,
            takenAt.toLocalDate(), "Control", "enologo", null, null));
        laboratory.saveResults(sampleCode, new ResultsRequest(
            List.of(new ResultInput("pH", "3,40", "", null, null)), "Borrador",
            takenAt.toLocalDate(), "Internal laboratory", "Meter", "Electrode", null));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void movesTheSampleAndItsResultsToTheRightDeposit() {
        SampleResponse moved = laboratory.reassignDeposit(sampleCode, rightDeposit, "Se anotó en el depósito equivocado");

        assertEquals(rightDeposit, moved.originDeposit());
        assertEquals(rightContent, moved.contentCode());
        assertEquals(1, moved.results().size(), "the pH result travels with the sample");

        assertTrue(laboratory.listByContent(rightContent).stream().anyMatch(item -> item.code().equals(sampleCode)));
        assertTrue(laboratory.listByContent(wrongContent).isEmpty(), "it no longer hangs from the wrong wine");

        // The curve of the right wine now carries the reading, and the wrong one is empty again.
        assertEquals(1, tracking.series(List.of(rightContent), List.of("PH"), null, null, false).points().size());
        assertTrue(tracking.series(List.of(wrongContent), List.of("PH"), null, null, false).points().isEmpty());

        String reason = jdbc.queryForObject(
            "select reason from audit_log where action = 'SAMPLE_REASSIGNED' order by created_at desc limit 1", String.class);
        assertEquals("Se anotó en el depósito equivocado", reason);
    }

    @Test
    void rejectsADepositThatHeldNothingWhenTheSampleWasTaken() {
        String emptyDeposit = "R-E-" + suffix;
        deposits.create(new DepositRequest(emptyDeposit, "CENTRO-NORTE", "NAVE-A", "7", new BigDecimal("800"), "Steel", false));

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
            () -> laboratory.reassignDeposit(sampleCode, emptyDeposit, "Prueba"));
        assertTrue(error.getMessage().contains("Ningún contenido ocupaba"), error.getMessage());

        assertEquals(wrongDeposit, laboratory.get(sampleCode).originDeposit(), "nothing moved");
    }

    @Test
    void rejectsTheDepositItAlreadyBelongsTo() {
        assertThrows(BusinessRuleException.class,
            () -> laboratory.reassignDeposit(sampleCode, wrongDeposit, "Prueba"));
    }

    @Test
    void laboratoryStaffCannotReassign() {
        authenticateAs("laboratorio");

        assertThrows(AccessDeniedException.class,
            () -> laboratory.reassignDeposit(sampleCode, rightDeposit, "Sin permiso"));
    }

    @Test
    void aValidatedAnalysisCanBeInvalidatedWithAReason() {
        laboratory.saveResults(sampleCode, new ResultsRequest(
            List.of(new ResultInput("pH", "3,40", "", null, null),
                new ResultInput("Densidad", "1,05", "g/mL", null, null),
                new ResultInput("Acidez volátil", "0,44", "g/L", null, null),
                new ResultInput("Azúcares reductores", "136,6", "g/L", null, null),
                new ResultInput("Ácido málico", "1,61", "g/L", null, null),
                new ResultInput("Acidez total TH2", "5,79", "g/L como tartárico", null, null),
                new ResultInput("Etanol", "5,27", "% vol.", null, null),
                new ResultInput("Glucosa más fructosa", "129", "g/L", null, null),
                new ResultInput("Acidez total", "3,78", "g/L como tartárico", null, null),
                new ResultInput("CO2 disuelto", "8023", "mg/L", null, null)),
            "Pendiente validar", LocalDate.now(TIMEZONE), "Internal laboratory", "Meter", "Electrode", null));
        assertEquals("Validado", laboratory.validate(sampleCode, "Revisado").status());

        var invalidated = laboratory.invalidate(sampleCode, "La sonda estaba descalibrada");

        assertEquals("Invalidado", invalidated.status());
        assertEquals("La sonda estaba descalibrada", invalidated.validationNote());
        assertTrue(jdbc.queryForObject("select exists(select 1 from audit_log where action = 'ANALYSIS_INVALIDATED')",
            Boolean.class), "the reason is kept in the audit log");
    }

    @Test
    void onlyASuperAdministratorDeletesAnAnalysisForGood() {
        assertThrows(AccessDeniedException.class, () -> laboratory.deleteSample(sampleCode, "Sin permiso"));

        jdbc.update("insert into app_user_role (user_id, role_id) "
            + "select u.id, r.id from app_user u, role r where u.username = 'enologo' and r.code = 'SUPER_ADMIN' "
            + "on conflict do nothing");

        laboratory.deleteSample(sampleCode, "Fila duplicada de una importación");

        assertEquals(0, jdbc.queryForObject("select count(*) from sample where code = ?", Integer.class, sampleCode).intValue());
        assertEquals(0, jdbc.queryForObject("select count(*) from analysis a join sample s on s.id = a.sample_id "
            + "where s.code = ?", Integer.class, sampleCode).intValue());
        assertTrue(jdbc.queryForObject("select exists(select 1 from audit_log where action = 'SAMPLE_DELETED')",
            Boolean.class), "what was deleted stays in the audit log");
        assertThrows(NotFoundException.class, () -> laboratory.get(sampleCode));
    }

    private LotRequest lot(String code) {
        return new LotRequest(code, LocalDate.now(TIMEZONE).getYear(), "Tinto", "Vino tranquilo", "enologo",
            LocalDate.now(TIMEZONE).minusDays(5), "Reception", List.of("Tempranillo"));
    }

    private void authenticateAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }
}
