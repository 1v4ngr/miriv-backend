package coop.miriv.enology.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.assistant.mcp.DepositStatusDto.DepositStatus;
import coop.miriv.enology.assistant.mcp.DepositStatusDto.ParameterTrend;
import coop.miriv.enology.assistant.mcp.DepositStatusService;
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
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.support.IntegrationTest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** The snapshot the assistant reads: content, 30-day trend per parameter and pending work. */
@Transactional
class DepositStatusServiceTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired DepositStatusService status;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired LaboratoryService laboratory;
    @Autowired AppUserDetailsService userDetailsService;

    @BeforeEach
    void authenticate() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void reportsThirtyDayTrendOfEveryMeasuredParameter() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String deposit = "S-D-" + suffix;
        deposits.create(new DepositRequest(deposit, "CENTRO-NORTE", "NAVE-A", "9", new BigDecimal("2000"), "Steel", true));
        lots.create(new CreateLotRequest(new LotRequest("S-L-" + suffix, LocalDate.now(TIMEZONE).getYear(),
            "Tinto", "Vino tranquilo", "enologo", LocalDate.now(TIMEZONE), "Reception", List.of("Tempranillo")),
            new LotEntryRequest(deposit, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(10))));
        String content = deposits.get(deposit).occupations().getFirst().contentCode();

        // Three samples over a week: density falling, temperature rising.
        record Sample(int daysAgo, String density, String temperature) {}
        List<Sample> samples = List.of(new Sample(6, "1,060", "22,0"), new Sample(3, "1,030", "23,5"),
            new Sample(1, "1,010", "24,5"));
        for (Sample sample : samples) {
            String code = "S-M-" + suffix + "-" + sample.daysAgo();
            LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusDays(sample.daysAgo()).withNano(0);
            laboratory.create(new NewSampleRequest(code, deposit, content, null, null, takenAt,
                takenAt.toLocalDate(), "Ampliado", "enologo", null, null));
            laboratory.saveResults(code, new ResultsRequest(List.of(
                new ResultInput("Densidad", sample.density(), "g/mL", null, null),
                new ResultInput("Temperatura del contenido", sample.temperature(), "°C", null, null)),
                "Borrador", takenAt.toLocalDate(), "Internal laboratory", "Meter", "Probe", null));
        }

        DepositStatus snapshot = status.status(deposit, null);

        assertEquals(deposit, snapshot.deposit());
        assertEquals(content, snapshot.content());
        assertEquals(30, snapshot.windowDays());
        assertEquals(50, snapshot.fillPercent()); // 1000 of 2000 L
        assertTrue(snapshot.refrigerated());

        ParameterTrend density = trend(snapshot, "DENSITY");
        assertEquals(3, density.readings());
        assertEquals("falling", density.trend());
        assertEquals(0, density.latest().compareTo(new BigDecimal("1.0100")), "latest density: " + density.latest());
        assertEquals(0, density.change().compareTo(new BigDecimal("-0.0500")), "change: " + density.change());
        assertTrue(density.perDay().signum() < 0, "density should fall per day: " + density.perDay());
        assertEquals(3, density.history().size());
        assertTrue(density.history().getFirst().at().isBefore(density.history().getLast().at()));

        ParameterTrend temperature = trend(snapshot, "CONTENT_TEMPERATURE");
        assertEquals("rising", temperature.trend());
        assertEquals(0, temperature.change().compareTo(new BigDecimal("2.5")), "change: " + temperature.change());
        assertNotNull(temperature.latestAt());
    }

    @Test
    void emptyDepositSaysSoInsteadOfFailing() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String deposit = "S-E-" + suffix;
        deposits.create(new DepositRequest(deposit, "CENTRO-NORTE", "NAVE-A", "8", new BigDecimal("500"), "Steel", false));

        DepositStatus snapshot = status.status(deposit, null);

        assertEquals(deposit, snapshot.deposit());
        assertTrue(snapshot.parameters().isEmpty());
        assertTrue(snapshot.notes().stream().anyMatch(note -> note.contains("no tiene contenido activo")), snapshot.notes().toString());
    }

    private static ParameterTrend trend(DepositStatus snapshot, String parameter) {
        return snapshot.parameters().stream()
            .filter(item -> item.parameter().equals(parameter))
            .findFirst()
            .orElseThrow(() -> new AssertionError(parameter + " missing in " + snapshot.parameters().stream()
                .map(ParameterTrend::parameter).toList()));
    }
}
