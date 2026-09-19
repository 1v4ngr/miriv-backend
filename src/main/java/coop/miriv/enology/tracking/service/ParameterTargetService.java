package coop.miriv.enology.tracking.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetView;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editable warning / critical ranges per parameter (V29) and the rule that picks the most specific one. */
@Service
public class ParameterTargetService {

    /** A stored range; category / phase null means "applies to any". */
    public record Target(String parameter, String categoryCode, String phase, String contentCode, BigDecimal warnMin,
                         BigDecimal warnMax, BigDecimal critMin, BigDecimal critMax) {

        /** Higher = more specific; -1 = does not apply to this content. */
        int score(String category, String contentPhase, String content) {
            boolean contentMatches = contentCode != null && contentCode.equalsIgnoreCase(content);
            if (contentCode != null && !contentMatches) return -1;
            boolean categoryMatches = categoryCode != null && categoryCode.equalsIgnoreCase(category);
            boolean phaseMatches = phase != null && contentPhase != null && phase.equalsIgnoreCase(contentPhase);
            if (categoryCode != null && !categoryMatches) return -1;
            if (phase != null && !phaseMatches) return -1;
            return (contentMatches ? 4 : 0) + (categoryMatches ? 2 : 0) + (phaseMatches ? 1 : 0);
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserContext context;

    public ParameterTargetService(JdbcTemplate jdbc, AuditService audit, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public List<TargetView> list() {
        return jdbc.query(VIEW_SQL + " order by p.name, c.name nulls first, t.phase nulls first", (rs, n) -> view(rs));
    }

    @Transactional
    public TargetView create(TargetRequest r) {
        UUID id = UUID.randomUUID();
        validate(r);
        UUID parameterId = parameterId(r.parameter());
        UUID categoryId = categoryId(r.categoryCode());
        UUID contentId = contentId(r.contentCode());
        requireUnique(parameterId, categoryId, blankToNull(r.phase()), contentId, null);
        try {
            jdbc.update("insert into parameter_target(id, parameter_id, category_id, phase, content_unit_id, warn_min, warn_max, crit_min, "
                    + "crit_max, note) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, parameterId,
                categoryId, blankToNull(r.phase()), contentId, r.warnMin(), r.warnMax(), r.critMin(), r.critMax(),
                blankToNull(r.note()));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        audit.record("parameter_target", id, "TARGET_CREATED", r.parameter() + describe(r));
        return get(id);
    }

    @Transactional
    public TargetView update(UUID id, TargetRequest r) {
        TargetView before = get(id);
        validate(r);
        UUID parameterId = parameterId(r.parameter());
        UUID categoryId = categoryId(r.categoryCode());
        UUID contentId = contentId(r.contentCode());
        requireUnique(parameterId, categoryId, blankToNull(r.phase()), contentId, id);
        try {
            jdbc.update("update parameter_target set parameter_id = ?, category_id = ?, phase = ?, content_unit_id = ?, warn_min = ?, warn_max = ?, "
                    + "crit_min = ?, crit_max = ?, note = ?, updated_at = now() where id = ?", parameterId,
                categoryId, blankToNull(r.phase()), contentId, r.warnMin(), r.warnMax(), r.critMin(), r.critMax(),
                blankToNull(r.note()), id);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        TargetView after = get(id);
        audit.record("parameter_target", id, "TARGET_UPDATED", r.parameter(), before, after);
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        TargetView before = get(id);
        jdbc.update("delete from parameter_target where id = ?", id);
        audit.record("parameter_target", id, "TARGET_DELETED", before.parameter(), before, null);
    }

    /** All ranges, for resolving against many contents without a query each. */
    @Transactional(readOnly = true)
    public List<Target> all() {
        return jdbc.query("select p.code param, c.code cat, t.phase, cu.code content, t.warn_min, t.warn_max, t.crit_min, t.crit_max "
                + "from parameter_target t join parameter p on p.id = t.parameter_id "
                + "left join internal_category c on c.id = t.category_id left join content_unit cu on cu.id = t.content_unit_id",
            (rs, n) -> new Target(rs.getString("param"), rs.getString("cat"), rs.getString("phase"), rs.getString("content"),
                rs.getBigDecimal("warn_min"), rs.getBigDecimal("warn_max"), rs.getBigDecimal("crit_min"),
                rs.getBigDecimal("crit_max")));
    }

    /** Most specific range for a parameter, content category and fermentation phase, or null when none applies. */
    public static Target resolve(List<Target> targets, String parameter, String category, String phase, String content) {
        return targets.stream()
            .filter(t -> t.parameter().equals(parameter) && t.score(category, phase, content) >= 0)
            .max(Comparator.comparingInt(t -> t.score(category, phase, content)))
            .orElse(null);
    }

    /** OK / WARN / CRIT, or UNKNOWN when a "&lt; limit" result cannot tell (also NONE without value or range). */
    public static String evaluate(BigDecimal value, String qualifier, BigDecimal limit, Target range) {
        if (range == null) return "NONE";
        boolean qualified = qualifier != null && !qualifier.equals("NONE");
        if (qualified) {
            if ("NOT_MEASURED".equals(qualifier)) return "NONE";
            if (!"LESS_THAN".equals(qualifier) || limit == null) return "UNKNOWN";
            // "< L": the real value is somewhere below L. It is certainly low when L is under a lower bound...
            if (range.critMin() != null && limit.compareTo(range.critMin()) <= 0) return "CRIT";
            if (range.warnMin() != null && limit.compareTo(range.warnMin()) <= 0) return "WARN";
            // ...certainly fine only when no lower bound exists and L is within the upper ones.
            boolean lowerBound = range.critMin() != null || range.warnMin() != null;
            boolean upperReachable = (range.critMax() != null && limit.compareTo(range.critMax()) > 0)
                || (range.warnMax() != null && limit.compareTo(range.warnMax()) > 0);
            return lowerBound || upperReachable ? "UNKNOWN" : "OK";
        }
        if (value == null) return "NONE";
        if (range.critMin() != null && value.compareTo(range.critMin()) < 0) return "CRIT";
        if (range.critMax() != null && value.compareTo(range.critMax()) > 0) return "CRIT";
        if (range.warnMin() != null && value.compareTo(range.warnMin()) < 0) return "WARN";
        if (range.warnMax() != null && value.compareTo(range.warnMax()) > 0) return "WARN";
        return "OK";
    }

    private static final String VIEW_SQL = "select t.id, p.code param, p.name param_name, p.reference_unit unit, c.code cat_code, "
        + "c.name cat_name, t.phase, cu.code content_code, t.warn_min, t.warn_max, t.crit_min, t.crit_max, t.note from parameter_target t "
        + "join parameter p on p.id = t.parameter_id left join internal_category c on c.id = t.category_id "
        + "left join content_unit cu on cu.id = t.content_unit_id";

    private TargetView get(UUID id) {
        return jdbc.query(VIEW_SQL + " where t.id = ?", (rs, n) -> view(rs), id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Objetivo no encontrado."));
    }

    private TargetView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TargetView(rs.getObject("id", UUID.class), rs.getString("param"), rs.getString("param_name"),
            rs.getString("unit"), rs.getString("cat_code"), rs.getString("cat_name"), rs.getString("phase"), rs.getString("content_code"),
            rs.getBigDecimal("warn_min"), rs.getBigDecimal("warn_max"), rs.getBigDecimal("crit_min"),
            rs.getBigDecimal("crit_max"), rs.getString("note"));
    }

    private UUID parameterId(String code) {
        if (code == null || code.isBlank()) throw new BusinessRuleException("Elige un parámetro.");
        return jdbc.query("select id from parameter where lower(code) = lower(?)", (rs, n) -> rs.getObject(1, UUID.class),
                code.trim()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Parámetro no encontrado: " + code));
    }

    private UUID categoryId(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.query("select id from internal_category where lower(code) = lower(?)",
                (rs, n) -> rs.getObject(1, UUID.class), code.trim()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Categoría no encontrada: " + code));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String describe(TargetRequest r) {
        return String.format(Locale.ROOT, " aviso [%s, %s] crítico [%s, %s]", r.warnMin(), r.warnMax(), r.critMin(), r.critMax());
    }

    /** Checked up front (the DB constraints stay as a safety net) so a bad request never aborts the transaction. */
    private static void validate(TargetRequest r) {
        if (r.warnMin() == null && r.warnMax() == null && r.critMin() == null && r.critMax() == null) {
            throw new BusinessRuleException("Define al menos un límite.");
        }
        if (!ordered(r.critMin(), r.warnMin()) || !ordered(r.warnMin(), r.warnMax()) || !ordered(r.warnMax(), r.critMax())
            || !ordered(r.critMin(), r.warnMax()) || !ordered(r.warnMin(), r.critMax()) || !ordered(r.critMin(), r.critMax())) {
            throw new BusinessRuleException("Los límites deben ir en orden: crítico mín. ≤ aviso mín. ≤ aviso máx. ≤ crítico máx.");
        }
    }

    private static boolean ordered(BigDecimal low, BigDecimal high) {
        return low == null || high == null || low.compareTo(high) <= 0;
    }

    private void requireUnique(UUID parameterId, UUID categoryId, String phase, UUID contentId, UUID excludeId) {
        String zero = "'00000000-0000-0000-0000-000000000000'";
        Integer same = jdbc.queryForObject("select count(*) from parameter_target where parameter_id = ? "
                + "and coalesce(category_id, " + zero + ") = coalesce(?::uuid, " + zero + ") "
                + "and coalesce(content_unit_id, " + zero + ") = coalesce(?::uuid, " + zero + ") "
                + "and coalesce(phase, '') = coalesce(?, '') and (?::uuid is null or id <> ?::uuid)",
            Integer.class, parameterId, categoryId, contentId, phase, excludeId, excludeId);
        if (same != null && same > 0) {
            throw new ConflictException("DUPLICATE_TARGET", "Ya existe un objetivo para ese parámetro, categoría, fase y contenido.");
        }
    }

    /** Content of the current center by code, or null when none is given. */
    private UUID contentId(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.query("select c.id from content_unit c join lot l on l.id = c.lot_id where lower(c.code) = lower(?) and l.center_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), code.trim(), context.centerId()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Contenido no encontrado: " + code));
    }

    /** Creates or replaces the range fixed for one parameter of one content. */
    @Transactional
    public TargetView saveForContent(String contentCode, TargetRequest r) {
        UUID contentId = contentId(contentCode);
        TargetRequest scoped = new TargetRequest(r.parameter(), null, null, contentCode, r.warnMin(), r.warnMax(), r.critMin(), r.critMax(), r.note());
        UUID existing = jdbc.query("select t.id from parameter_target t join parameter p on p.id = t.parameter_id "
                + "where t.content_unit_id = ? and lower(p.code) = lower(?)", (rs, n) -> rs.getObject(1, UUID.class), contentId, r.parameter())
            .stream().findFirst().orElse(null);
        return existing == null ? create(scoped) : update(existing, scoped);
    }

    @Transactional
    public void deleteForContent(String contentCode, UUID id) {
        UUID contentId = contentId(contentCode);
        Integer owned = jdbc.queryForObject("select count(*) from parameter_target where id = ? and content_unit_id = ?", Integer.class, id, contentId);
        if (owned == null || owned == 0) throw new NotFoundException("Objetivo no encontrado en este contenido.");
        delete(id);
    }

    /** Ranges fixed for one content (only those, not the category or global ones). */
    @Transactional(readOnly = true)
    public List<TargetView> listForContent(String contentCode) {
        UUID id = contentId(contentCode);
        return jdbc.query(VIEW_SQL + " where t.content_unit_id = ? order by p.name", (rs, n) -> view(rs), id);
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_parameter_target_scope")) {
            return new ConflictException("DUPLICATE_TARGET", "Ya existe un objetivo para ese parámetro, categoría y fase.");
        }
        return new BusinessRuleException("Define al menos un límite y respeta el orden: crítico mín. ≤ aviso mín. ≤ aviso máx. ≤ crítico máx.");
    }
}
