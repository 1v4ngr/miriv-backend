package coop.miriv.enology.tracking.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertCondition;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertRuleRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertRuleView;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alert rules ("fermentation finished", "possible stop"…) and their evaluation against the latest analyses of
 * every occupied tank. Rules belong to a center and can be narrowed to a category, to fermentation phases or to
 * one content (values fixed for a single wine). An alert is hidden once acknowledged, until a newer sample re-raises it.
 */
@Service
public class AlertService {

    private static final Set<String> TYPES = Set.of("LTE", "GTE", "STABLE");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "CRIT");
    private static final int HISTORY = 10;
    private static final int MAX_CONDITIONS = 4;
    private static final Map<String, Integer> SEVERITY_RANK = Map.of("CRIT", 3, "WARN", 2, "INFO", 1);

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;
    private final JsonMapper json;

    public AlertService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ rules

    private static final String RULE_SQL = "select r.id, r.name, r.severity, r.conditions, r.phases, r.active, c.code cat_code, c.name cat_name, "
        + "cu.code content_code from alert_rule r left join internal_category c on c.id = r.category_id "
        + "left join content_unit cu on cu.id = r.content_unit_id where r.center_id = ?";

    @Transactional(readOnly = true)
    public List<AlertRuleView> rules() {
        return jdbc.query(RULE_SQL + " order by r.name", (rs, n) -> rule(rs), context.centerId());
    }

    /** Rules that watch one specific content. */
    @Transactional(readOnly = true)
    public List<AlertRuleView> rulesForContent(String contentCode) {
        return jdbc.query(RULE_SQL + " and cu.id = ? order by r.name", (rs, n) -> rule(rs), context.centerId(), contentId(contentCode));
    }

    @Transactional
    public AlertRuleView create(AlertRuleRequest request) {
        validate(request);
        UUID center = context.centerId();
        if (nameTaken(center, request.name().trim(), null)) throw new ConflictException("DUPLICATE_NAME", "Ya existe un aviso con ese nombre.");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into alert_rule(id, center_id, name, severity, conditions, category_id, content_unit_id, phases, active, created_by) "
                + "values (?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), ?, ?)", id, center, request.name().trim(), request.severity(),
            json.writeValueAsString(request.conditions()), categoryId(request.categoryCode()), contentId(request.contentCode()),
            json.writeValueAsString(phases(request)), request.active() == null || request.active(), context.userId());
        audit.record("alert_rule", id, "ALERT_RULE_CREATED", request.name().trim());
        return get(id);
    }

    @Transactional
    public AlertRuleView update(UUID id, AlertRuleRequest request) {
        AlertRuleView before = get(id);
        validate(request);
        if (nameTaken(context.centerId(), request.name().trim(), id)) throw new ConflictException("DUPLICATE_NAME", "Ya existe un aviso con ese nombre.");
        jdbc.update("update alert_rule set name = ?, severity = ?, conditions = cast(? as jsonb), category_id = ?, content_unit_id = ?, "
                + "phases = cast(? as jsonb), active = ?, updated_at = now() where id = ? and center_id = ?",
            request.name().trim(), request.severity(), json.writeValueAsString(request.conditions()), categoryId(request.categoryCode()),
            contentId(request.contentCode()), json.writeValueAsString(phases(request)), request.active() == null || request.active(), id, context.centerId());
        AlertRuleView after = get(id);
        audit.record("alert_rule", id, "ALERT_RULE_UPDATED", after.name(), before, after);
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        AlertRuleView before = get(id);
        jdbc.update("delete from alert_rule where id = ? and center_id = ?", id, context.centerId());
        audit.record("alert_rule", id, "ALERT_RULE_DELETED", before.name(), before, null);
    }

    private AlertRuleView get(UUID id) {
        return jdbc.query(RULE_SQL + " and r.id = ?", (rs, n) -> rule(rs), context.centerId(), id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Aviso no encontrado."));
    }

    private AlertRuleView rule(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<AlertCondition> conditions = json.readValue(rs.getString("conditions"), new TypeReference<List<AlertCondition>>() {});
        List<String> phases = json.readValue(rs.getString("phases"), new TypeReference<List<String>>() {});
        return new AlertRuleView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("severity"), conditions,
            rs.getString("cat_code"), rs.getString("cat_name"), rs.getString("content_code"), phases, rs.getBoolean("active"));
    }

    private void validate(AlertRuleRequest r) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 120) throw new BusinessRuleException("Pon un nombre al aviso (máx. 120 caracteres).");
        if (r.severity() == null || !SEVERITIES.contains(r.severity())) throw new BusinessRuleException("Gravedad no válida.");
        if (r.conditions() == null || r.conditions().isEmpty() || r.conditions().size() > MAX_CONDITIONS) {
            throw new BusinessRuleException("Un aviso necesita entre 1 y " + MAX_CONDITIONS + " condiciones.");
        }
        for (AlertCondition c : r.conditions()) {
            if (c.type() == null || !TYPES.contains(c.type())) throw new BusinessRuleException("Tipo de condición no válido.");
            Integer known = jdbc.queryForObject("select count(*) from parameter where code = ?", Integer.class, c.parameter());
            if (known == null || known == 0) throw new NotFoundException("Parámetro no encontrado: " + c.parameter());
            if (!"STABLE".equals(c.type()) && c.value() == null) throw new BusinessRuleException("Falta el valor de la condición.");
            if ("STABLE".equals(c.type()) && (c.days() == null || c.days() < 1 || c.days() > 30 || c.tolerance() == null || c.tolerance().signum() < 0)) {
                throw new BusinessRuleException("«Estable» necesita entre 1 y 30 días y una tolerancia no negativa.");
            }
        }
    }

    private List<String> phases(AlertRuleRequest r) { return r.phases() == null ? List.of() : r.phases(); }

    private boolean nameTaken(UUID center, String name, UUID excluding) {
        Integer n = jdbc.queryForObject("select count(*) from alert_rule where center_id = ? and lower(name) = lower(?) and (?::uuid is null or id <> ?::uuid)",
            Integer.class, center, name, excluding, excluding);
        return n != null && n > 0;
    }

    private UUID categoryId(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.query("select id from internal_category where lower(code) = lower(?)", (rs, n) -> rs.getObject(1, UUID.class), code.trim())
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Categoría no encontrada: " + code));
    }

    private UUID contentId(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.query("select c.id from content_unit c join lot l on l.id = c.lot_id where lower(c.code) = lower(?) and l.center_id = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code.trim(), context.centerId()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Contenido no encontrado: " + code));
    }

    // ------------------------------------------------------------------ evaluation

    private record Tank(UUID id, String code, String deposit, String categoryCode, String category, String phase) {}
    private record Reading(Instant at, BigDecimal value, String qualifier, BigDecimal limit, String sample) {}
    private record Hit(Instant since, String sample, String detail) {}

    /** Alerts that hold right now for the tanks the user can read, not yet acknowledged, most serious first. */
    @Transactional(readOnly = true)
    public List<AlertView> alerts() {
        List<AlertRuleView> rules = rules().stream().filter(AlertRuleView::active).toList();
        if (rules.isEmpty()) return List.of();
        List<Tank> tanks = tanks();
        if (tanks.isEmpty()) return List.of();
        Map<String, List<Reading>> history = history(tanks, rules);
        Map<String, String> names = parameterNames();
        Map<String, String> acked = new HashMap<>();
        jdbc.query("select rule_id, content_unit_id, sample_code from alert_ack", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
            acked.put(rs.getObject(1, UUID.class) + "|" + rs.getObject(2, UUID.class), rs.getString(3)));

        List<AlertView> out = new ArrayList<>();
        for (AlertRuleView rule : rules) {
            for (Tank tank : tanks) {
                if (!applies(rule, tank)) continue;
                Hit hit = evaluate(rule, tank, history, names);
                if (hit == null) continue;
                if (hit.sample().equals(acked.get(rule.id() + "|" + tank.id()))) continue;
                out.add(new AlertView(rule.id(), rule.name(), rule.severity(), tank.code(), tank.deposit(), tank.category(), hit.since(), hit.sample(), hit.detail()));
            }
        }
        out.sort(Comparator.comparingInt((AlertView a) -> -SEVERITY_RANK.get(a.severity())).thenComparing(AlertView::since, Comparator.reverseOrder()));
        return out;
    }

    /** Hides the alert of a tank until a newer sample raises it again. */
    @Transactional
    public void acknowledge(UUID ruleId, String contentCode) {
        AlertRuleView rule = get(ruleId);
        Tank tank = tanks().stream().filter(t -> t.code().equalsIgnoreCase(contentCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Contenido no encontrado."));
        Hit hit = evaluate(rule, tank, history(List.of(tank), List.of(rule)), parameterNames());
        if (hit == null) throw new BusinessRuleException("El aviso ya no está activo.");
        jdbc.update("insert into alert_ack(rule_id, content_unit_id, sample_code, acknowledged_by_id) values (?, ?, ?, ?) "
                + "on conflict (rule_id, content_unit_id) do update set sample_code = excluded.sample_code, "
                + "acknowledged_by_id = excluded.acknowledged_by_id, acknowledged_at = now()", ruleId, tank.id(), hit.sample(), context.userId());
        audit.record("alert_rule", ruleId, "ALERT_ACKNOWLEDGED", tank.code() + " · " + hit.sample());
    }

    private boolean applies(AlertRuleView rule, Tank tank) {
        if (rule.contentCode() != null && !rule.contentCode().equalsIgnoreCase(tank.code())) return false;
        if (rule.categoryCode() != null && !rule.categoryCode().equalsIgnoreCase(tank.categoryCode())) return false;
        if (rule.phases() != null && !rule.phases().isEmpty()) {
            String phase = tank.phase() == null ? "NOT_EVALUATED" : tank.phase();
            return rule.phases().stream().anyMatch(item -> item.equalsIgnoreCase(phase));
        }
        return true;
    }

    private Hit evaluate(AlertRuleView rule, Tank tank, Map<String, List<Reading>> history, Map<String, String> names) {
        Instant latestAt = null;
        String latestSample = null;
        List<String> parts = new ArrayList<>();
        for (AlertCondition condition : rule.conditions()) {
            List<Reading> readings = history.getOrDefault(tank.id() + "|" + condition.parameter(), List.of());
            if (readings.isEmpty()) return null;
            Reading newest = readings.getFirst();
            String name = names.getOrDefault(condition.parameter(), condition.parameter());
            String part = switch (condition.type()) {
                case "LTE" -> holdsLte(newest, condition.value()) ? name + " " + text(newest) + " ≤ " + plain(condition.value()) : null;
                case "GTE" -> newest.qualifier().equals("NONE") && newest.value() != null && newest.value().compareTo(condition.value()) >= 0
                    ? name + " " + text(newest) + " ≥ " + plain(condition.value()) : null;
                default -> stable(readings, condition, name);
            };
            if (part == null) return null;
            parts.add(part);
            if (latestAt == null || newest.at().isAfter(latestAt)) { latestAt = newest.at(); latestSample = newest.sample(); }
        }
        return new Hit(latestAt, latestSample, String.join(" · ", parts));
    }

    /** "< L" results count as low when L itself is under the threshold. */
    private static boolean holdsLte(Reading reading, BigDecimal threshold) {
        if (reading.qualifier().equals("NONE")) return reading.value() != null && reading.value().compareTo(threshold) <= 0;
        return reading.qualifier().equals("LESS_THAN") && reading.limit() != null && reading.limit().compareTo(threshold) <= 0;
    }

    /** Needs at least two numeric readings covering (most of) the window, all within the tolerance. */
    private static String stable(List<Reading> readings, AlertCondition condition, String name) {
        Reading newest = readings.getFirst();
        Instant from = newest.at().minus(Duration.ofDays(condition.days()));
        List<Reading> window = readings.stream().filter(r -> !r.at().isBefore(from)).toList();
        if (window.size() < 2 || window.stream().anyMatch(r -> !r.qualifier().equals("NONE") || r.value() == null)) return null;
        Reading oldest = window.getLast();
        if (Duration.between(oldest.at(), newest.at()).toHours() < condition.days() * 24 * 0.75) return null;
        BigDecimal max = window.stream().map(Reading::value).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal min = window.stream().map(Reading::value).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal spread = max.subtract(min);
        if (spread.compareTo(condition.tolerance()) > 0) return null;
        return name + " estable (variación " + plain(spread) + " en " + condition.days() + " d)";
    }

    private static String text(Reading reading) { return reading.value() == null ? "< " + plain(reading.limit()) : plain(reading.value()); }

    private static String plain(BigDecimal value) { return value == null ? "?" : value.stripTrailingZeros().toPlainString(); }

    private List<Tank> tanks() {
        ZoneFilter zones = context.readZoneFilter("d");
        List<Object> args = new ArrayList<>();
        args.add(context.centerId());
        args.addAll(zones.zoneIds());
        return jdbc.query("""
            select c.id, c.code, d.code deposit_code, cat.code cat_code, cat.name cat_name,
                   (select coalesce(fs.confirmed_status, fs.estimated_status) from fermentation_state fs
                     where fs.content_unit_id = c.id and fs.process = 'ALCOHOLIC'::fermentation_process) phase
              from content_unit c
              join occupation o on o.content_unit_id = c.id and o.end_at is null
              join deposit d on d.id = o.deposit_id
              left join internal_category cat on cat.id = c.category_id
             where c.active and d.center_id = ?""" + zones.sql() + " order by d.code",
            (rs, n) -> new Tank(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("deposit_code"), rs.getString("cat_code"),
                rs.getString("cat_name"), rs.getString("phase")), args.toArray());
    }

    /** Newest-first readings of the parameters the rules use, per tank: key "tankId|parameterCode". */
    private Map<String, List<Reading>> history(List<Tank> tanks, List<AlertRuleView> rules) {
        Set<String> codes = new java.util.LinkedHashSet<>();
        rules.forEach(rule -> rule.conditions().forEach(condition -> codes.add(condition.parameter())));
        List<Object> args = new ArrayList<>(tanks.stream().map(Tank::id).toList());
        args.addAll(codes);
        String sql = """
            select * from (
              select s.content_unit_id, p.code param_code, s.taken_at, r.numeric_value, r.qualifier::text qualifier, r.qualifier_limit, s.code sample_code,
                     row_number() over (partition by s.content_unit_id, r.parameter_id order by s.taken_at desc, r.created_at desc) rn
                from result r
                join analysis a on a.id = r.analysis_id
                join sample s on s.id = a.sample_id
                join parameter p on p.id = r.parameter_id
               where r.is_current and a.status <> 'INVALIDATED'::analysis_status
                 and s.content_unit_id in (%s) and p.code in (%s)
            ) t where rn <= %d order by rn""".formatted(marks(tanks.size()), marks(codes.size()), HISTORY);
        Map<String, List<Reading>> out = new HashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Timestamp at = rs.getTimestamp("taken_at");
            out.computeIfAbsent(rs.getObject("content_unit_id", UUID.class) + "|" + rs.getString("param_code"), k -> new ArrayList<>())
                .add(new Reading(at.toInstant(), rs.getBigDecimal("numeric_value"), rs.getString("qualifier"), rs.getBigDecimal("qualifier_limit"), rs.getString("sample_code")));
        }, args.toArray());
        return out;
    }

    private Map<String, String> parameterNames() {
        Map<String, String> names = new HashMap<>();
        jdbc.query("select code, name from parameter", (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getString(1), rs.getString(2)));
        return names;
    }

    private static String marks(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
}
