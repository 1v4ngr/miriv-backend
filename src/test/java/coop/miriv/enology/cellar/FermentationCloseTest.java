package coop.miriv.enology.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.dto.StateReviewRequest;
import coop.miriv.enology.cellar.service.ContentService;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Closing the alcoholic fermentation is when the must becomes wine. */
@Transactional
class FermentationCloseTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired ContentService contents;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserDetailsService userDetailsService;

    private String content;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String deposit = "F-D-" + suffix;
        deposits.create(new DepositRequest(deposit, "CENTRO-NORTE", "NAVE-A", "4", new BigDecimal("2000"), "Steel", false));
        lots.create(new CreateLotRequest(new LotRequest("F-L-" + suffix, LocalDate.now(TIMEZONE).getYear(), "Mosto",
            "Vino tranquilo", "enologo", LocalDate.now(TIMEZONE).minusDays(15), "Vendimia", List.of("Tempranillo")),
            new LotEntryRequest(deposit, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(15))));
        content = deposits.get(deposit).occupations().getFirst().contentCode();
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void closingTheFermentationTurnsTheMustIntoWine() {
        assertEquals("Mosto", category());

        contents.review(content, new StateReviewRequest("alcoholic", "Finalizada",
            "Densidad estable en 0,993 tres días", "Tinto"));

        assertEquals("Tinto", category());
        assertEquals("FINISHED", contents.get(content).alcoholic().confirmation());
        assertTrue(jdbc.queryForObject("select exists(select 1 from audit_log where action = 'CONTENT_RECLASSIFIED' "
            + "and reason = 'Densidad estable en 0,993 tres días')", Boolean.class));
    }

    @Test
    void theStateCanBeClosedWithoutReclassifying() {
        contents.review(content, new StateReviewRequest("alcoholic", "Finalizada", "Terminada", null));

        assertEquals("Mosto", category());
    }

    @Test
    void reclassifyingIsOnlyForAFinishedAlcoholicFermentation() {
        BusinessRuleException error = assertThrows(BusinessRuleException.class, () ->
            contents.review(content, new StateReviewRequest("alcoholic", "Activa", "Aún fermenta", "Tinto")));
        assertTrue(error.getMessage().contains("finalizada"), error.getMessage());
        assertEquals("Mosto", category(), "nothing changed");
    }

    @Test
    void anUnknownCategoryIsRejected() {
        assertThrows(BusinessRuleException.class, () ->
            contents.review(content, new StateReviewRequest("alcoholic", "Finalizada", "Motivo", "Champán")));
    }

    private String category() {
        return jdbc.queryForObject("select cat.name from content_unit cu join internal_category cat "
            + "on cat.id = cu.category_id where cu.code = ?", String.class, content);
    }
}
