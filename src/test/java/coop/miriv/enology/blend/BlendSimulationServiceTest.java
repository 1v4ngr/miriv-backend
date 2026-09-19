package coop.miriv.enology.blend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import coop.miriv.enology.blend.dto.BlendDto.BlendRequest;
import coop.miriv.enology.blend.dto.BlendDto.BlendView;
import coop.miriv.enology.blend.dto.BlendDto.ConvertRequest;
import coop.miriv.enology.blend.service.BlendSimulationService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.AppUserDetailsService;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F7-04: saved simulations and their conversion into planned transfers + a guiding task. */
@Transactional
class BlendSimulationServiceTest extends IntegrationTest {

    private static final UUID CENTER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired BlendSimulationService blends;
    @Autowired AppUserDetailsService userDetails;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    private UUID admin;
    private UUID zone;

    @BeforeEach
    void setUp() {
        actAs("enologo");
        admin = jdbc.queryForObject("select id from app_user where username = 'admin'", UUID.class);
        zone = UUID.randomUUID();
        jdbc.update("insert into zone(id, center_id, code, name) values (?, ?, 'Z-BLD', 'Zona mezclas')", zone, CENTER);
        content("T1");
        content("T2");
        deposit("D-EMPTY", 5000);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void actAs(String username) {
        AppUserPrincipal principal = (AppUserPrincipal) userDetails.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void deposit(String code, int capacity) {
        jdbc.update("insert into deposit(id, code, center_id, zone_id, useful_capacity_liters) values (?, ?, ?, ?, ?)",
            UUID.randomUUID(), code, CENTER, zone, capacity);
    }

    private void content(String suffix) {
        UUID category = jdbc.queryForObject("select id from internal_category where code = 'RED'", UUID.class);
        UUID color = jdbc.queryForObject("select id from color limit 1", UUID.class);
        UUID destination = jdbc.queryForObject("select id from destination limit 1", UUID.class);
        UUID lot = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID deposit = UUID.randomUUID();
        jdbc.update("insert into deposit(id, code, center_id, zone_id, useful_capacity_liters) values (?, ?, ?, ?, 10000)",
            deposit, "D-" + suffix, CENTER, zone);
        jdbc.update("insert into lot(id, code, campaign, category_id, color_id, destination_id, entry_date, responsible_id, center_id) "
            + "values (?, ?, 2026, ?, ?, ?, current_date, ?, ?)", lot, "L-" + suffix, category, color, destination, admin, CENTER);
        jdbc.update("insert into content_unit(id, code, lot_id, category_id, volume_liters) values (?, ?, ?, ?, 1000)",
            content, "C-" + suffix, lot, category);
        jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, 1000)",
            UUID.randomUUID(), content, deposit, Timestamp.from(Instant.now().minus(20, ChronoUnit.DAYS)));
    }

    private JsonNode payload(String components, String additions) {
        return json.readTree("{\"components\":[" + components + "],\"additions\":[" + additions + "]}");
    }

    private static String comp(String suffix, int liters) {
        return "{\"contentCode\":\"C-" + suffix + "\",\"depositCode\":\"D-" + suffix + "\",\"volumeLiters\":" + liters + "}";
    }

    private BlendView save(String name, String destination, JsonNode payload) {
        return blends.create(new BlendRequest(name, destination, payload, null, null));
    }

    private ConvertRequest convertRequest() {
        return new ConvertRequest("enologo", Instant.now().plus(1, ChronoUnit.DAYS), "MEDIUM");
    }

    @Test
    void createListGetAndOtherCentersAreInvisible() {
        var created = save("Mezcla 1", "D-EMPTY", payload(comp("T1", 600) + "," + comp("T2", 400), ""));
        assertEquals("DRAFT", created.status());
        assertEquals(1, blends.list().stream().filter(item -> item.id().equals(created.id())).count());
        assertEquals(2, blends.get(created.id()).payload().path("components").size());

        UUID otherCenter = UUID.randomUUID();
        jdbc.update("insert into center(id, code, name) values (?, ?, 'Otro')", otherCenter, "BLD-" + otherCenter.toString().substring(0, 8));
        UUID foreign = UUID.randomUUID();
        jdbc.update("insert into blend_simulation(id, center_id, name, payload) values (?, ?, 'Ajena', cast('{\"components\":[]}' as jsonb))", foreign, otherCenter);
        assertThrows(NotFoundException.class, () -> blends.get(foreign));
        assertTrue(blends.list().stream().noneMatch(item -> item.id().equals(foreign)));
    }

    @Test
    void validatesTheFormatAndUniqueNames() {
        assertThrows(BusinessRuleException.class, () -> save("Vacía", null, payload("", "")));
        assertThrows(BusinessRuleException.class, () -> save("Sin litros", null, json.readTree("{\"components\":[{\"contentCode\":\"C-T1\",\"depositCode\":\"D-T1\"}]}")));
        assertThrows(BusinessRuleException.class, () -> save("Adición rara", null, payload(comp("T1", 10), "{\"type\":\"LEJIA\",\"amount\":1}")));
        save("Única", null, payload(comp("T1", 10), ""));
        assertThrows(ConflictException.class, () -> save("única", null, payload(comp("T1", 10), "")));
    }

    @Test
    void convertCreatesPlannedTransfersAndATaskWithTheSteps() {
        var created = save("Mezcla tinta", "D-EMPTY", payload(comp("T1", 600) + "," + comp("T2", 1000),
            "{\"type\":\"POTASSIUM_METABISULFITE\",\"amount\":5}"));

        var converted = blends.convert(created.id(), convertRequest());

        assertEquals("CONVERTED", converted.status());
        assertNotNull(converted.taskCode());
        assertEquals(2, converted.plannedMovements().size());
        assertEquals(2, jdbc.queryForObject("select count(*) from movement where status = 'PLANNED'::movement_status "
            + "and planned_destination_deposit = 'D-EMPTY'", Integer.class));
        String description = jdbc.queryForObject("select description from task where code = ?", String.class, converted.taskCode());
        assertTrue(description.contains("1. Trasegar 600 L de D-T1 (C-T1) → D-EMPTY (autorizar mezcla). Movimiento previsto " + converted.plannedMovements().get(0)));
        assertTrue(description.contains("2. Trasegar 1000 L de D-T2 (C-T2) → D-EMPTY"));
        assertTrue(description.contains("3. Añadir metabisulfito potásico 5 g/hL en D-EMPTY"));
        assertTrue(description.contains("Simulación BLEND:" + created.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'CONVERT'", Integer.class, created.id()));
    }

    @Test
    void convertInsideAComponentDepositMovesOnlyTheOthers() {
        var created = save("En sitio", "D-T1", payload(comp("T1", 1000) + "," + comp("T2", 500), ""));
        var converted = blends.convert(created.id(), convertRequest());
        assertEquals(1, converted.plannedMovements().size());
        assertEquals("C-T1", jdbc.queryForObject("select c.code from task t join content_unit c on c.id = t.content_unit_id where t.code = ?",
            String.class, converted.taskCode()));
    }

    @Test
    void convertRefusesWaterMissingDestinationAndTooMuchWine() {
        var water = save("Con agua", "D-EMPTY", payload(comp("T1", 500), "{\"type\":\"WATER\",\"amount\":50}"));
        assertThrows(BusinessRuleException.class, () -> blends.convert(water.id(), convertRequest()));

        var noDestination = save("Sin destino", null, payload(comp("T1", 500), ""));
        assertThrows(BusinessRuleException.class, () -> blends.convert(noDestination.id(), convertRequest()));

        var tooMuch = save("Demasiado", "D-EMPTY", payload(comp("T1", 1200), ""));
        var error = assertThrows(BusinessRuleException.class, () -> blends.convert(tooMuch.id(), convertRequest()));
        assertEquals("D-T1: pides 1200 L y ahora hay 1000 L.", error.getMessage());
    }

    @Test
    void convertRefusesAnOccupiedOrTooSmallDestination() {
        var occupied = save("Ocupado", "D-T2", payload(comp("T1", 500), ""));
        var error = assertThrows(BusinessRuleException.class, () -> blends.convert(occupied.id(), convertRequest()));
        assertEquals("D-T2 está ocupado.", error.getMessage());

        deposit("D-SMALL", 1000);
        var small = save("Pequeño", "D-SMALL", payload(comp("T1", 800) + "," + comp("T2", 800), ""));
        assertThrows(BusinessRuleException.class, () -> blends.convert(small.id(), convertRequest()));

        var partial = save("Parcial", "D-T1", payload(comp("T1", 400) + "," + comp("T2", 400), ""));
        var partialError = assertThrows(BusinessRuleException.class, () -> blends.convert(partial.id(), convertRequest()));
        assertTrue(partialError.getMessage().contains("debe usar todo el volumen"));
    }

    @Test
    void aConvertedSimulationIsFrozenAndEditsCheckTheVersion() {
        var created = save("Congelar", "D-EMPTY", payload(comp("T1", 500), ""));
        var updated = blends.update(created.id(), new BlendRequest("Congelar", "D-EMPTY", payload(comp("T1", 600), ""), null, 0));
        assertEquals(1, updated.version());
        assertThrows(ConflictException.class, () -> blends.update(created.id(), new BlendRequest("Congelar", "D-EMPTY", payload(comp("T1", 700), ""), null, 0)));

        var converted = blends.convert(created.id(), convertRequest());
        assertThrows(BusinessRuleException.class, () -> blends.update(created.id(), new BlendRequest("Congelar", "D-EMPTY", payload(comp("T1", 1), ""), null, converted.version())));
        assertThrows(BusinessRuleException.class, () -> blends.delete(created.id()));
        assertEquals("Congelar (copia)", blends.duplicate(created.id()).name());
        assertEquals("DRAFT", blends.duplicate(created.id()).status());
    }

    @Test
    void longDescriptionsAreTrimmedInTheMiddle() {
        var steps = new java.util.ArrayList<String>();
        for (int i = 1; i <= 40; i++) steps.add(i + ". Trasegar 100 L de D-" + i + " (C-2026-" + i + ") → D-DEST (autorizar mezcla). Movimiento previsto MOV-2026-0000" + i);
        String text = BlendSimulationService.describe(steps, "Simulación BLEND:x");
        assertTrue(text.length() <= 1000);
        assertTrue(text.startsWith("1. Trasegar"));
        assertTrue(text.contains("\n…\n"));
        assertTrue(text.endsWith("Simulación BLEND:x"));
    }
}
