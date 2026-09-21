package coop.miriv.enology.blend.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.blend.dto.BlendDto.BlendRequest;
import coop.miriv.enology.blend.dto.BlendDto.BlendSummary;
import coop.miriv.enology.blend.dto.BlendDto.BlendView;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved blend simulations. The maths runs in the browser; the server stores the inputs and validates them.
 */
@Service
public class BlendSimulationService {

    static final int MAX_COMPONENTS = 20;
    private static final Set<String> ADDITION_TYPES = Set.of("WATER", "TARTARIC_ACID", "POTASSIUM_METABISULFITE", "SO2_SOLUTION");

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;
    private final JsonMapper json;
    private final ZoneId timezone;

    public BlendSimulationService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit, JsonMapper json,
                                  @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.json = json;
        this.timezone = ZoneId.of(timezone);
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<BlendSummary> list() {
        return jdbc.query("select b.id, b.name, b.status, b.destination_deposit_code, u.full_name author, b.updated_at "
                + "from blend_simulation b left join app_user u on u.id = b.user_id "
                + "where b.center_id = ? order by b.updated_at desc",
            (rs, n) -> new BlendSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("destination_deposit_code"), rs.getString("author"), rs.getTimestamp("updated_at").toInstant()),
            context.centerId());
    }

    @Transactional(readOnly = true)
    public BlendView get(UUID id) {
        return jdbc.query(VIEW_SQL + " where b.id = ? and b.center_id = ?", (rs, n) -> view(rs), id, context.centerId())
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Simulación no encontrada."));
    }

    @Transactional
    public BlendView create(BlendRequest r) {
        UUID center = context.centerId();
        validate(r.payload());
        requireFreeName(center, r.name().trim(), null);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into blend_simulation(id, center_id, user_id, name, destination_deposit_code, payload, result) "
                + "values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))",
            id, center, context.userId(), r.name().trim(), blankToNull(r.destinationDepositCode()), text(r.payload()), nullableText(r.result()));
        return get(id);
    }

    @Transactional
    public BlendView update(UUID id, BlendRequest r) {
        BlendView current = get(id);
        validate(r.payload());
        if (r.version() == null) throw new IllegalArgumentException("Falta la versión de la simulación.");
        requireFreeName(context.centerId(), r.name().trim(), id);
        int updated = jdbc.update("update blend_simulation set name = ?, destination_deposit_code = ?, payload = cast(? as jsonb), "
                + "result = cast(? as jsonb), version = version + 1, updated_at = now() where id = ? and center_id = ? and version = ?",
            r.name().trim(), blankToNull(r.destinationDepositCode()), text(r.payload()), nullableText(r.result()), id,
            context.centerId(), r.version());
        if (updated == 0) {
            throw new ConflictException("STALE_BLEND", "La simulación se ha modificado en otra ventana. Recarga para ver la última versión.");
        }
        return get(id);
    }

    @Transactional
    public BlendView duplicate(UUID id) {
        BlendView source = get(id);
        UUID center = context.centerId();
        String name = source.name() + " (copia)";
        for (int n = 2; nameTaken(center, name, null); n++) name = source.name() + " (copia " + n + ")";
        if (name.length() > 120) name = name.substring(0, 120);
        UUID copy = UUID.randomUUID();
        jdbc.update("insert into blend_simulation(id, center_id, user_id, name, destination_deposit_code, payload, result) "
                + "values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))",
            copy, center, context.userId(), name, source.destinationDepositCode(), text(source.payload()), nullableText(source.result()));
        return get(copy);
    }

    @Transactional
    public void delete(UUID id) {
        get(id);
        jdbc.update("delete from blend_simulation where id = ? and center_id = ?", id, context.centerId());
    }

    // ------------------------------------------------------------------ helpers

    private static final String VIEW_SQL = "select b.id, b.name, b.status, b.destination_deposit_code, b.payload, b.result, b.version, "
        + "b.updated_at, b.planned_movements, u.full_name author from blend_simulation b "
        + "left join app_user u on u.id = b.user_id";

    private BlendView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode movementsNode = json.readTree(rs.getString("planned_movements"));
        List<String> codes = new ArrayList<>();
        movementsNode.forEach(node -> codes.add(node.asString()));
        String result = rs.getString("result");
        return new BlendView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
            rs.getString("destination_deposit_code"), json.readTree(rs.getString("payload")), result == null ? null : json.readTree(result),
            rs.getString("author"), rs.getInt("version"), rs.getTimestamp("updated_at").toInstant(), codes);
    }

    private void validate(JsonNode payload) {
        JsonNode components = payload.path("components");
        if (!components.isArray() || components.isEmpty() || components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("La simulación no tiene un formato válido.");
        }
        for (JsonNode component : components) {
            if (!component.hasNonNull("contentCode") || !component.hasNonNull("depositCode") || !component.path("volumeLiters").isNumber()
                || component.path("volumeLiters").decimalValue().signum() < 0) {
                throw new IllegalArgumentException("La simulación no tiene un formato válido.");
            }
        }
        JsonNode additions = payload.path("additions");
        if (!additions.isMissingNode() && !additions.isNull()) {
            if (!additions.isArray()) throw new IllegalArgumentException("La simulación no tiene un formato válido.");
            for (JsonNode addition : additions) {
                if (!ADDITION_TYPES.contains(addition.path("type").asString()) || !addition.path("amount").isNumber()) {
                    throw new IllegalArgumentException("La simulación no tiene un formato válido.");
                }
            }
        }
        if (text(payload).length() > 200_000) throw new IllegalArgumentException("La simulación es demasiado grande.");
    }

    private String text(JsonNode node) { return json.writeValueAsString(node); }

    private String nullableText(JsonNode node) { return node == null || node.isNull() ? null : json.writeValueAsString(node); }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private boolean nameTaken(UUID center, String name, UUID excluding) {
        Integer n = jdbc.queryForObject("select count(*) from blend_simulation where center_id = ? and lower(name) = lower(?) "
            + "and (?::uuid is null or id <> ?::uuid)", Integer.class, center, name, excluding, excluding);
        return n != null && n > 0;
    }

    private void requireFreeName(UUID center, String name, UUID excluding) {
        if (nameTaken(center, name, excluding)) throw new ConflictException("DUPLICATE_NAME", "Ya existe una simulación con ese nombre.");
    }
}