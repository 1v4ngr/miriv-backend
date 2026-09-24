package coop.miriv.enology.laboratory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.ResultInput;
import coop.miriv.enology.laboratory.dto.ResultsRequest;
import coop.miriv.enology.laboratory.dto.SampleResponse;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** The inbox summary (one query) must show exactly what the full list shows, minus results and history. */
@Transactional
class SampleSummaryTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired LaboratoryService laboratory;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired AppUserDetailsService userDetailsService;

    @BeforeEach
    void authenticate() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void summaryMatchesTheFullListWithoutResults() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String deposit = "SS-D-" + suffix;
        deposits.create(new DepositRequest(deposit, "CENTRO-NORTE", "NAVE-A", "1", new BigDecimal("2000"), "Steel", false));
        lots.create(new CreateLotRequest(new LotRequest("SS-L-" + suffix, LocalDate.now(TIMEZONE).getYear(), "Tinto", "Vino tranquilo",
            "enologo", LocalDate.now(TIMEZONE), "Reception", List.of("Tempranillo")),
            new LotEntryRequest(deposit, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(5))));
        String content = deposits.get(deposit).occupations().getFirst().contentCode();
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusDays(1).withNano(0);
        String sample = "SS-M-" + suffix;
        laboratory.create(new NewSampleRequest(sample, deposit, content, null, null, takenAt, takenAt.toLocalDate(), "Ampliado", "enologo", null, null));
        laboratory.saveResults(sample, new ResultsRequest(List.of(new ResultInput("Densidad", "1,010", "g/mL", null, null)),
            "Borrador", takenAt.toLocalDate(), "Internal laboratory", "Meter", "Probe", null));

        Map<String, SampleResponse> full = laboratory.list().stream().collect(Collectors.toMap(SampleResponse::code, Function.identity()));
        List<SampleResponse> summary = laboratory.listSummary();

        assertEquals(full.size(), summary.size());
        assertTrue(summary.stream().anyMatch(item -> item.code().equals(sample)));
        for (SampleResponse item : summary) {
            SampleResponse expected = full.get(item.code());
            assertEquals(expected.currentDeposit(), item.currentDeposit(), item.code());
            assertEquals(expected.originDeposit(), item.originDeposit(), item.code());
            assertEquals(expected.completed(), item.completed(), item.code() + " completed");
            assertEquals(expected.required(), item.required(), item.code() + " required");
            assertEquals(expected.status(), item.status(), item.code());
            assertEquals(expected.takenAt(), item.takenAt(), item.code());
            assertTrue(item.results().isEmpty() && item.panelParameters().isEmpty(), "summary carries no results");
        }
    }
}
