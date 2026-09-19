package coop.miriv.enology.blend.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.blend.dto.BlendDto.BlendRequest;
import coop.miriv.enology.blend.dto.BlendDto.BlendSummary;
import coop.miriv.enology.blend.dto.BlendDto.BlendView;
import coop.miriv.enology.blend.dto.BlendDto.ConvertRequest;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.service.MovementService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.task.dto.CreateTaskRequest;
import coop.miriv.enology.task.dto.TaskResponse;
import coop.miriv.enology.task.service.TaskService;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Saved blend simulations. The maths runs in the browser; the server stores the inputs and, when a simulation
 * is converted, re-checks against the CURRENT cellar (volumes, destination) before creating the planned
 * transfers and the task that guides their execution.
 */
@Service
public class BlendSimulationService {

    static final int MAX_COMPONENTS = 20;
    private static final Set<String> ADDITION_TYPES = Set.of("WATER", "TARTARIC_ACID", "POTASSIUM_METABISULFITE", "SO2_SOLUTION");
    private static final Set<String> PRIORITIES = Set.of("NONE", "LOW", "MEDIUM", "HIGH");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.5");
    private static final int DESCRIPTION_LIMIT = 1000;

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;
    private final JsonMapper json;
    private final TaskService tasks;
    private final MovementService movements;
    private final ZoneId timezone;

    public BlendSimulationService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit, JsonMapper json,
                                  TaskService tasks, MovementService movements, @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.json = json;
        this.tasks = tasks;
        this.movements = movements;
        this.timezone = ZoneId.of(timezone);
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<BlendSummary> list() {
        return jdbc.query("select b.id, b.name, b.status, b.destination_deposit_code, u.full_name author, b.updated_at, t.code task_code "
                + "from blend_simulation b left join app_user u on u.id = b.user_id left join task t on t.id = b.task_id "
                + "where b.center_id = ? order by b.updated_at desc",
            (rs, n) -> new BlendSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("destination_deposit_code"), rs.getString("author"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("task_code")), context.centerId());
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
        if ("CONVERTED".equals(current.status())) {
            throw new BusinessRuleException("Esta simulación ya se convirtió en tarea; duplícala para cambiarla.");
        }
        validate(r.payload());
        if (r.version() == null) throw new BusinessRuleException("Falta la versión de la simulación.");
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
        BlendView current = get(id);
        if ("CONVERTED".equals(current.status())) throw new BusinessRuleException("Una simulación convertida en tarea no se puede borrar.");
        jdbc.update("delete from blend_simulation where id = ? and center_id = ?", id, context.centerId());
    }

    // ------------------------------------------------------------------ conversion

    private record Line(String contentCode, String depositCode, BigDecimal liters, BigDecimal current) {}

    /**
     * Creates one PLANNED transfer per source deposit plus a task that lists the steps. Everything is
     * re-validated against the cellar as it is now; the transaction rolls back if any part fails.
     */
    @Transactional
    public BlendView convert(UUID id, ConvertRequest request) {
        BlendView simulation = get(id);
        UUID center = context.centerId();
        if (!"DRAFT".equals(simulation.status())) throw new BusinessRuleException("Esta simulación ya se convirtió en tarea.");
        String destination = simulation.destinationDepositCode();
        if (destination == null || destination.isBlank()) throw new BusinessRuleException("Elige el depósito destino antes de convertir.");
        String priority = request.priority() == null || request.priority().isBlank() ? "MEDIUM" : request.priority();
        if (!PRIORITIES.contains(priority)) throw new BusinessRuleException("Prioridad de tarea no soportada.");

        JsonNode payload = simulation.payload();
        for (JsonNode addition : payload.path("additions")) {
            if ("WATER".equals(addition.path("type").asString()) && addition.path("amount").asDouble() > 0) {
                throw new BusinessRuleException("Una simulación con agua no se puede convertir en tarea (Reg. UE 1308/2013, anexo VIII).");
            }
        }

        // 1. Every source still holds the wine, in enough quantity.
        List<Line> lines = new ArrayList<>();
        for (JsonNode component : payload.path("components")) {
            BigDecimal liters = component.path("volumeLiters").decimalValue();
            if (liters.signum() <= 0) continue;
            String content = component.path("contentCode").asString();
            String deposit = component.path("depositCode").asString();
            BigDecimal current = jdbc.query("select o.volume_liters from occupation o join deposit d on d.id = o.deposit_id "
                    + "join content_unit c on c.id = o.content_unit_id where o.end_at is null and d.center_id = ? "
                    + "and lower(d.code) = lower(?) and lower(c.code) = lower(?)", (rs, n) -> rs.getBigDecimal(1), center, deposit, content)
                .stream().findFirst().orElseThrow(() -> new BusinessRuleException(content + " ya no está en " + deposit + "."));
            if (liters.compareTo(current) > 0) {
                throw new BusinessRuleException(deposit + ": pides " + plain(liters) + " L y ahora hay " + plain(current) + " L.");
            }
            lines.add(new Line(content, deposit, liters, current));
        }
        if (lines.isEmpty()) throw new BusinessRuleException("Añade al menos un depósito con litros.");

        // 2. The destination exists and can take the blend.
        record Dest(BigDecimal capacity, BigDecimal occupiedLiters, String content) {}
        Dest dest = jdbc.query("select d.useful_capacity_liters, o.volume_liters, c.code from deposit d "
                + "left join occupation o on o.deposit_id = d.id and o.end_at is null "
                + "left join content_unit c on c.id = o.content_unit_id where d.center_id = ? and d.active and lower(d.code) = lower(?)",
            (rs, n) -> new Dest(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)), center, destination)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Depósito destino no encontrado."));
        Line inPlace = lines.stream().filter(line -> line.depositCode().equalsIgnoreCase(destination)).findFirst().orElse(null);
        BigDecimal total = lines.stream().map(Line::liters).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inPlace != null) {
            if (inPlace.current().subtract(inPlace.liters()).compareTo(TOLERANCE) > 0) {
                throw new BusinessRuleException("La mezcla se hace en " + destination + ": su componente debe usar todo el volumen.");
            }
        } else if (dest.occupiedLiters() != null) {
            throw new BusinessRuleException(destination + " está ocupado.");
        }
        if (dest.capacity() != null && total.compareTo(dest.capacity()) > 0) {
            throw new BusinessRuleException("La mezcla no cabe en " + destination + ".");
        }

        // 3. One PLANNED transfer per source that is not the destination itself.
        var when = request.dueAt().atZone(timezone);
        List<String> steps = new ArrayList<>();
        List<String> movementCodes = new ArrayList<>();
        int step = 0;
        for (Line line : lines) {
            if (line.depositCode().equalsIgnoreCase(destination)) continue;
            step++;
            String movementCode = movements.register(new MovementRequest("Trasiego", when.toLocalDate(), when.toLocalTime().withNano(0),
                request.responsible(), "Mezcla «" + simulation.name() + "»", line.depositCode(), destination, line.liters(), BigDecimal.ZERO,
                "blend:" + id + ":" + step, true, null, null, true)).code();
            movementCodes.add(movementCode);
            steps.add(step + ". Trasegar " + plain(line.liters()) + " L de " + line.depositCode() + " (" + line.contentCode() + ") → "
                + destination + " (autorizar mezcla). Movimiento previsto " + movementCode);
        }
        for (JsonNode addition : payload.path("additions")) {
            String label = switch (addition.path("type").asString()) {
                case "TARTARIC_ACID" -> "ácido tartárico " + plain(addition.path("amount").decimalValue()) + " g/hL";
                case "POTASSIUM_METABISULFITE" -> "metabisulfito potásico " + plain(addition.path("amount").decimalValue()) + " g/hL";
                case "SO2_SOLUTION" -> "SO₂ en solución " + plain(addition.path("amount").decimalValue()) + " mg/L";
                default -> null;
            };
            if (label != null) steps.add(++step + ". Añadir " + label + " en " + destination + " tras la mezcla.");
        }

        // 4. The task that guides the execution.
        String description = describe(steps, "Simulación BLEND:" + id);
        String destinationContent = inPlace != null ? inPlace.contentCode() : null;
        TaskResponse task = tasks.create(new CreateTaskRequest("Ejecutar mezcla " + simulation.name(), destination, destinationContent,
            request.responsible(), request.dueAt(), priority, "Mezcla ejecutada y muestreada", description));
        UUID taskId = jdbc.queryForObject("select id from task where code = ?", UUID.class, task.code());
        jdbc.update("update blend_simulation set status = 'CONVERTED', task_id = ?, planned_movements = cast(? as jsonb), "
                + "version = version + 1, updated_at = now() where id = ?", taskId, json.writeValueAsString(movementCodes), id);
        audit.record("blend_simulation", id, "CONVERT", "Tarea " + task.code() + " y movimientos previstos " + String.join(", ", movementCodes));
        return get(id);
    }

    /** Keeps the task description within its limit by dropping middle steps first. */
    public static String describe(List<String> steps, String footer) {
        String full = String.join("\n", steps) + "\n" + footer;
        if (full.length() <= DESCRIPTION_LIMIT) return full;
        List<String> kept = new ArrayList<>(steps);
        while (kept.size() > 2 && (String.join("\n", kept) + "\n…\n" + footer).length() > DESCRIPTION_LIMIT) kept.remove(kept.size() / 2);
        return String.join("\n", kept.subList(0, kept.size() / 2 + kept.size() % 2)) + "\n…\n"
            + String.join("\n", kept.subList(kept.size() / 2 + kept.size() % 2, kept.size())) + "\n" + footer;
    }

    // ------------------------------------------------------------------ helpers

    private static final String VIEW_SQL = "select b.id, b.name, b.status, b.destination_deposit_code, b.payload, b.result, b.version, "
        + "b.updated_at, b.planned_movements, u.full_name author, t.code task_code from blend_simulation b "
        + "left join app_user u on u.id = b.user_id left join task t on t.id = b.task_id";

    private BlendView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode movementsNode = json.readTree(rs.getString("planned_movements"));
        List<String> codes = new ArrayList<>();
        movementsNode.forEach(node -> codes.add(node.asString()));
        String result = rs.getString("result");
        return new BlendView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
            rs.getString("destination_deposit_code"), json.readTree(rs.getString("payload")), result == null ? null : json.readTree(result),
            rs.getString("author"), rs.getInt("version"), rs.getTimestamp("updated_at").toInstant(), rs.getString("task_code"), codes);
    }

    private void validate(JsonNode payload) {
        JsonNode components = payload.path("components");
        if (!components.isArray() || components.isEmpty() || components.size() > MAX_COMPONENTS) {
            throw new BusinessRuleException("La simulación no tiene un formato válido.");
        }
        for (JsonNode component : components) {
            if (!component.hasNonNull("contentCode") || !component.hasNonNull("depositCode") || !component.path("volumeLiters").isNumber()
                || component.path("volumeLiters").decimalValue().signum() < 0) {
                throw new BusinessRuleException("La simulación no tiene un formato válido.");
            }
        }
        JsonNode additions = payload.path("additions");
        if (!additions.isMissingNode() && !additions.isNull()) {
            if (!additions.isArray()) throw new BusinessRuleException("La simulación no tiene un formato válido.");
            for (JsonNode addition : additions) {
                if (!ADDITION_TYPES.contains(addition.path("type").asString()) || !addition.path("amount").isNumber()) {
                    throw new BusinessRuleException("La simulación no tiene un formato válido.");
                }
            }
        }
        if (text(payload).length() > 200_000) throw new BusinessRuleException("La simulación es demasiado grande.");
    }

    private String text(JsonNode node) { return json.writeValueAsString(node); }

    private String nullableText(JsonNode node) { return node == null || node.isNull() ? null : json.writeValueAsString(node); }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String plain(BigDecimal value) { return value.setScale(0, RoundingMode.HALF_UP).toPlainString(); }

    private boolean nameTaken(UUID center, String name, UUID excluding) {
        Integer n = jdbc.queryForObject("select count(*) from blend_simulation where center_id = ? and lower(name) = lower(?) "
            + "and (?::uuid is null or id <> ?::uuid)", Integer.class, center, name, excluding, excluding);
        return n != null && n > 0;
    }

    private void requireFreeName(UUID center, String name, UUID excluding) {
        if (nameTaken(center, name, excluding)) throw new ConflictException("DUPLICATE_NAME", "Ya existe una simulación con ese nombre.");
    }
}
