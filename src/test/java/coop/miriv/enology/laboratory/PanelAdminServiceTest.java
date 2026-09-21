package coop.miriv.enology.laboratory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.cellar.service.LotService;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRow;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.CategoryRef;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelCreateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelUpdateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelView;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterRef;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterRequest;
import coop.miriv.enology.laboratory.service.AnalysisImportService;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import coop.miriv.enology.laboratory.service.PanelAdminService;
import coop.miriv.enology.support.IntegrationTest;
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

/** Templates are managed, and each content category gets the one it needs: must and wine differ. */
@Transactional
class PanelAdminServiceTest extends IntegrationTest {

    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Madrid");

    @Autowired PanelAdminService panels;
    @Autowired LaboratoryService laboratory;
    @Autowired AnalysisImportService imports;
    @Autowired DepositService deposits;
    @Autowired LotService lots;
    @Autowired AppUserDetailsService userDetailsService;

    private String suffix;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername("enologo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void theWineWorksheetParametersExist() {
        var codes = panels.parameters().stream().map(p -> p.code()).toList();
        for (String code : List.of("TARTARIC_ACID", "CITRIC_ACID", "SORBIC_ACID", "GLYCEROL", "COLOR_INTENSITY",
                "ABS_420", "ABS_520", "ABS_620", "TOTAL_POLYPHENOL_INDEX")) {
            assertTrue(codes.contains(code), code + " missing");
        }
    }

    @Test
    void mustAndWineAreOfferedDifferentDefaultTemplates() {
        assertEquals("CONTROL", panels.panelsForCategory("MUST").getFirst().code());
        assertEquals("WINE", panels.panelsForCategory("RED").getFirst().code());
        assertEquals("WINE", panels.panelsForCategory("Blanco").getFirst().code(), "category names work too");
        PanelView wine = panels.panelsForCategory("RED").getFirst();
        assertTrue(wine.parameters().stream().anyMatch(p -> p.code().equals("COLOR_INTENSITY")));
    }

    @Test
    void parametersAreCreatedAndEditedButCodesStayUnique() {
        String code = "TEST_" + suffix;
        panels.createParameter(new ParameterRequest(code, "Parámetro de prueba", "mg/L", 1, null, null, null, true));
        assertThrows(ConflictException.class, () ->
            panels.createParameter(new ParameterRequest(code, "Otro", "mg/L", 1, null, null, null, true)));

        var updated = panels.updateParameter(code, new ParameterRequest(null, "Renombrado", "g/L", 2,
            BigDecimal.ZERO, BigDecimal.TEN, "desc", false));

        assertEquals("Renombrado", updated.name());
        assertEquals("g/L", updated.unit());
        assertFalse(updated.active());
    }

    @Test
    void aTemplateIsBuiltWithOrderedParametersAndTakesOverTheCategoryDefault() {
        String code = "P_" + suffix;
        panels.createPanel(new PanelCreateRequest(code, "Rosado rápido", "Prueba"));

        PanelView updated = panels.updatePanel(code, new PanelUpdateRequest("Rosado rápido", "Prueba", true,
            List.of(new ParameterRef("PH", true), new ParameterRef("ETHANOL", false), new ParameterRef("COLOR_INTENSITY", true)),
            List.of(new CategoryRef("ROSE", true))));

        assertEquals(List.of("PH", "ETHANOL", "COLOR_INTENSITY"), updated.parameters().stream().map(p -> p.code()).toList());
        assertTrue(updated.parameters().getFirst().required());
        assertFalse(updated.parameters().get(1).required());
        // The new default replaces the wine analysis as the proposal for rosé, and only for rosé.
        assertEquals(code, panels.panelsForCategory("ROSE").getFirst().code());
        assertEquals("WINE", panels.panelsForCategory("RED").getFirst().code());
    }

    @Test
    void aDeactivatedTemplateCannotBeUsedForNewSamples() {
        String code = "OFF_" + suffix;
        panels.createPanel(new PanelCreateRequest(code, "Antigua", null));
        panels.updatePanel(code, new PanelUpdateRequest("Antigua", null, false, List.of(new ParameterRef("PH", true)), List.of()));
        String deposit = mustDeposit("MUST");
        String content = deposits.get(deposit).occupations().getFirst().contentCode();
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusHours(1).withNano(0);

        assertThrows(NotFoundException.class, () -> laboratory.create(new NewSampleRequest("X-" + suffix, deposit, content,
            null, null, takenAt, takenAt.toLocalDate(), code, "enologo", null, null)));
    }

    @Test
    void anImportedRowUsesTheTemplateOfWhatIsInTheDeposit() {
        String must = mustDeposit("MUST");
        String wine = mustDeposit("RED");
        LocalDateTime takenAt = LocalDateTime.now(TIMEZONE).minusHours(1).withNano(0);

        var response = imports.execute(new ImportRequest(List.of(
            new ImportRow(1, must, takenAt, Map.of("DENSITY", "1,05"), null),
            new ImportRow(2, wine, takenAt, Map.of("ETHANOL", "11,03", "COLOR_INTENSITY", "4,6"), null))));

        assertEquals(2, response.imported());
        String mustSample = response.rows().stream().filter(r -> r.reference() == 1).findFirst().orElseThrow().sampleCode();
        String wineSample = response.rows().stream().filter(r -> r.reference() == 2).findFirst().orElseThrow().sampleCode();
        assertEquals("Control fermentativo", laboratory.get(mustSample).panel());
        assertEquals("Análisis de vino", laboratory.get(wineSample).panel());
    }

    private String mustDeposit(String category) {
        String code = "PA-" + category.substring(0, 2) + "-" + suffix;
        deposits.create(new DepositRequest(code, "CENTRO-NORTE", "NAVE-A", "1", new BigDecimal("2000"), "Steel", false));
        String categoryName = "MUST".equals(category) ? "Mosto" : "Tinto";
        lots.create(new CreateLotRequest(new LotRequest("PA-L-" + category.substring(0, 2) + "-" + suffix,
            LocalDate.now(TIMEZONE).getYear(), categoryName, "Vino tranquilo", "enologo",
            LocalDate.now(TIMEZONE).minusDays(5), "Vendimia", List.of("Tempranillo")),
            new LotEntryRequest(code, new BigDecimal("1000"), LocalDate.now(TIMEZONE).minusDays(5))));
        return code;
    }
}
