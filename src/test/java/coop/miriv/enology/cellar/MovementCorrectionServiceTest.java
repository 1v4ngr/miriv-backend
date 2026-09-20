package coop.miriv.enology.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.cellar.service.MovementCorrectionService;
import coop.miriv.enology.cellar.service.MovementService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

/** Correcting a movement entered wrong: move its date, or undo it altogether. */
@Transactional
class MovementCorrectionServiceTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired MovementCorrectionService corrections;
    @Autowired MovementService movements;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired LaboratoryService laboratory;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserDetailsService userDetailsService;

    private String suffix;
    private String source;
    private String target;
    private String lotCode;

    @BeforeEach
    void setUp() {
        authenticateAs("enologo");
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        source = "M-S-" + suffix;
        target = "M-T-" + suffix;
        lotCode = "M-L-" + suffix;
        deposits.create(new DepositRequest(source, "CENTRO-NORTE", "NAVE-A", "1", new BigDecimal("2000"), "Steel", false));
        deposits.create(new DepositRequest(target, "CENTRO-NORTE", "NAVE-A", "2", new BigDecimal("2000"), "Steel", false));
        lots.create(new CreateLotRequest(new LotRequest(lotCode, LocalDate.now(TIMEZONE).getYear(), "Tinto",
            "Vino tranquilo", "enologo", LocalDate.now(TIMEZONE).minusDays(10), "Recepción", List.of("Tempranillo")),
            new LotEntryRequest(source, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(10))));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void reschedulingAnEntryMovesTheOccupationAndTheLotDate() {
        String entry = jdbc.queryForObject("select m.code from movement m join movement_line ml on ml.movement_id = m.id "
            + "join content_unit cu on cu.id = ml.destination_content_unit_id where cu.lot_id = "
            + "(select id from lot where code = ?)", String.class, lotCode);
        LocalDate corrected = LocalDate.now(TIMEZONE).minusDays(12);

        corrections.reschedule(entry, corrected, LocalTime.of(8, 30), "La entrada fue dos días antes");

        assertEquals(corrected, jdbc.queryForObject("select entry_date from lot where code = ?", LocalDate.class, lotCode));
        LocalDateTime start = jdbc.queryForObject("select o.start_at at time zone 'Europe/Madrid' from occupation o "
            + "join deposit d on d.id = o.deposit_id where d.code = ?", LocalDateTime.class, source);
        assertEquals(corrected.atTime(8, 30), start);
        assertTrue(auditHas("MOVEMENT_RESCHEDULED"));
    }

    @Test
    void undoingATransferPutsTheWineBack() {
        grantSuperAdmin("enologo");
        var movement = transfer(new BigDecimal("300"));
        assertEquals(0, new BigDecimal("700").compareTo(volumeIn(source)));
        assertEquals(0, new BigDecimal("300").compareTo(volumeIn(target)));

        corrections.undo(movement, "Me equivoqué de depósito");

        assertEquals(0, new BigDecimal("1000").compareTo(volumeIn(source)), "the source gets its litres back");
        assertEquals(0, jdbc.queryForObject("select count(*) from occupation o join deposit d on d.id = o.deposit_id "
            + "where d.code = ? and o.end_at is null", Integer.class, target).intValue(), "the destination is empty again");
        assertEquals("AVAILABLE", statusOf(target));
        assertEquals("OCCUPIED", statusOf(source));
        assertEquals(0, jdbc.queryForObject("select count(*) from movement where code = ?", Integer.class, movement).intValue());
        assertTrue(auditHas("MOVEMENT_UNDONE"));
    }

    @Test
    void undoingAFullTransferReopensTheSourceOccupation() {
        grantSuperAdmin("enologo");
        var movement = transfer(new BigDecimal("1000"));
        assertEquals("PENDING_CLEANING", statusOf(source));

        corrections.undo(movement, "Trasiego que nunca ocurrió");

        assertEquals(0, new BigDecimal("1000").compareTo(volumeIn(source)));
        assertEquals("OCCUPIED", statusOf(source));
        assertEquals("AVAILABLE", statusOf(target));
    }

    @Test
    void undoingIsRefusedWhenTheWineWasAlreadyAnalysed() {
        grantSuperAdmin("enologo");
        var movement = transfer(new BigDecimal("300"));
        String content = jdbc.queryForObject("select cu.code from content_unit cu join occupation o on o.content_unit_id = cu.id "
            + "join deposit d on d.id = o.deposit_id where d.code = ? and o.end_at is null", String.class, target);
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).withNano(0);
        laboratory.create(new NewSampleRequest("M-M-" + suffix, target, content, null, null, takenAt,
            takenAt.toLocalDate(), "Control", "enologo", null, null));

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
            () -> corrections.undo(movement, "Prueba"));
        assertTrue(error.getMessage().contains("analíticas"), error.getMessage());
        assertEquals(0, new BigDecimal("700").compareTo(volumeIn(source)), "nothing moved");
    }

    @Test
    void onlyASuperAdministratorMayUndo() {
        var movement = transfer(new BigDecimal("300"));

        assertThrows(AccessDeniedException.class, () -> corrections.undo(movement, "Sin permiso"),
            "an enologist without SUPER_ADMIN must not undo movements");
    }

    @Test
    void reschedulingIsRefusedWhenALaterMovementExists() {
        var first = transfer(new BigDecimal("200"));
        // A second transfer out of the same source, later in time and into a free deposit.
        String other = "M-O-" + suffix;
        deposits.create(new DepositRequest(other, "CENTRO-NORTE", "NAVE-A", "3", new BigDecimal("2000"), "Steel", false));
        movements.register(new MovementRequest("Trasiego", LocalDate.now(TIMEZONE),
            LocalTime.now(TIMEZONE).withNano(0).minusHours(1), "enologo", "Segundo trasiego", source, other,
            new BigDecimal("100"), BigDecimal.ZERO, "key-" + UUID.randomUUID(), false, null, null, false));

        BusinessRuleException error = assertThrows(BusinessRuleException.class,
            () -> corrections.reschedule(first, LocalDate.now(TIMEZONE),
                LocalTime.now(TIMEZONE).withNano(0).minusMinutes(1), "Tarde"));
        assertTrue(error.getMessage().contains("posterior"), error.getMessage());
    }

    private String transfer(BigDecimal liters) {
        return movements.register(request(liters, LocalDateTime.now(TIMEZONE).minusHours(3))).code();
    }

    private MovementRequest request(BigDecimal liters, LocalDateTime when) {
        return new MovementRequest("Trasiego", when.toLocalDate(), when.toLocalTime().withNano(0), "enologo",
            "Trasiego de prueba", source, target, liters, BigDecimal.ZERO, "key-" + UUID.randomUUID(), false,
            null, null, false);
    }

    private BigDecimal volumeIn(String depositCode) {
        return jdbc.queryForObject("select coalesce(sum(o.volume_liters), 0) from occupation o "
            + "join deposit d on d.id = o.deposit_id where d.code = ? and o.end_at is null",
            BigDecimal.class, depositCode);
    }

    private String statusOf(String depositCode) {
        return jdbc.queryForObject("select status::text from deposit where code = ?", String.class, depositCode);
    }

    private boolean auditHas(String action) {
        return jdbc.queryForObject("select exists(select 1 from audit_log where action = ?)", Boolean.class, action);
    }

    /** SUPER_ADMIN is checked live against the database, so granting the role is enough. */
    private void grantSuperAdmin(String username) {
        jdbc.update("insert into app_user_role (user_id, role_id) "
            + "select u.id, r.id from app_user u, role r where u.username = ? and r.code = 'SUPER_ADMIN' "
            + "on conflict do nothing", username);
    }

    private void authenticateAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }
}
