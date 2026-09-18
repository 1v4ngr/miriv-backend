package coop.miriv.enology.plan.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.plan.dto.CreatePlanRequest;
import coop.miriv.enology.plan.dto.PlanResponse;
import coop.miriv.enology.plan.dto.PlanVersionRequest;
import coop.miriv.enology.plan.dto.PlanVersionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private static final Set<String> INTENTS = Set.of("PLANNED", "NOT_DESIRED", "PENDING_DECISION");

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;

    public PlanService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PlanResponse get(String contentCode) {
        ContentScope content = content(contentCode);
        List<PlanRow> plans = jdbc.query("select p.id, p.name, d.name as destination, "
                + "v.version_number from elaboration_plan p "
                + "left join destination d on d.id = p.destination_id "
                + "left join plan_version v on v.id = p.current_version_id "
                + "where p.content_unit_id = ?",
            (rs, index) -> new PlanRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("destination"), rs.getInt("version_number")), content.id());
        if (plans.isEmpty()) throw new NotFoundException("Plan de elaboración no encontrado.");
        PlanRow plan = plans.getFirst();
        List<PlanVersionResponse> versions = jdbc.query("select v.version_number, v.alcoholic_strategy, v.yeast, "
                + "v.inoculation_date, v.sugar_target_g_per_l, v.malolactic_intent::text, "
                + "v.malolactic_strategy, v.bacteria, v.malolactic_expected_at, "
                + "v.temperature_min_celsius, v.temperature_max_celsius, panel.code as sampling_panel, "
                + "v.sampling_frequency_days, u.full_name as author, v.reason, v.effective_at "
                + "from plan_version v left join analysis_panel panel on panel.id = v.sampling_panel_id "
                + "join app_user u on u.id = v.author_id where v.plan_id = ? order by v.version_number desc",
            (rs, index) -> version(rs), plan.id());
        return new PlanResponse(content.code(), plan.name(), plan.destination(), plan.currentVersion(), versions);
    }

    @Transactional
    public PlanResponse create(String contentCode, CreatePlanRequest request) {
        ContentScope content = content(contentCode);
        if (!content.active()) throw new BusinessRuleException("No se puede crear un plan para contenido inactivo.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from elaboration_plan where content_unit_id = ?)",
            Boolean.class, content.id()))) throw new ConflictException("El contenido ya tiene un plan.");
        validate(request.version());
        UUID destinationId = destinationId(request.destination());
        UUID planId = UUID.randomUUID();
        UUID actor = context.userId();
        jdbc.update("insert into elaboration_plan(id, content_unit_id, name, destination_id, responsible_id) "
                + "values (?, ?, ?, ?, ?)", planId, content.id(), request.name().trim(), destinationId, actor);
        insertVersion(planId, 1, actor, request.version());
        audit.record("elaboration_plan", planId, "PLAN_CREATED", request.name().trim());
        return get(contentCode);
    }

    @Transactional
    public PlanResponse addVersion(String contentCode, PlanVersionRequest request) {
        ContentScope content = content(contentCode);
        if (!content.active()) throw new BusinessRuleException("No se puede revisar un plan para contenido inactivo.");
        validate(request);
        List<UUID> plans = jdbc.query("select id from elaboration_plan where content_unit_id = ? for update",
            (rs, index) -> rs.getObject(1, UUID.class), content.id());
        if (plans.isEmpty()) throw new NotFoundException("Plan de elaboración no encontrado.");
        UUID planId = plans.getFirst();
        Integer nextNumber = jdbc.queryForObject("select coalesce(max(version_number), 0) + 1 "
            + "from plan_version where plan_id = ?", Integer.class, planId);
        insertVersion(planId, nextNumber, context.userId(), request);
        audit.record("elaboration_plan", planId, "PLAN_VERSIONED", "Versión " + nextNumber + " · " + request.reason().trim());
        return get(contentCode);
    }

    private void insertVersion(UUID planId, int number, UUID actor, PlanVersionRequest request) {
        UUID panelId = panelId(request.samplingPanel());
        UUID versionId = UUID.randomUUID();
        jdbc.update("insert into plan_version(id, plan_id, version_number, alcoholic_strategy, yeast, "
                + "inoculation_date, sugar_target_g_per_l, malolactic_intent, malolactic_strategy, bacteria, "
                + "malolactic_expected_at, temperature_min_celsius, temperature_max_celsius, "
                + "sampling_panel_id, sampling_frequency_days, author_id, reason, effective_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, cast(? as malolactic_intent), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            versionId, planId, number, blankToNull(request.alcoholicStrategy()), blankToNull(request.yeast()),
            request.inoculationDate(), request.sugarTargetGramsPerLiter(), request.malolacticIntent(),
            blankToNull(request.malolacticStrategy()), blankToNull(request.bacteria()), request.malolacticExpectedAt(),
            request.temperatureMinCelsius(), request.temperatureMaxCelsius(), panelId,
            request.samplingFrequencyDays(), actor, request.reason().trim(), Timestamp.from(request.effectiveAt()));
        jdbc.update("update elaboration_plan set current_version_id = ? where id = ?", versionId, planId);
    }

    private PlanVersionResponse version(ResultSet rs) throws SQLException {
        return new PlanVersionResponse(rs.getInt("version_number"), rs.getString("alcoholic_strategy"),
            rs.getString("yeast"), rs.getDate("inoculation_date") == null ? null : rs.getDate("inoculation_date").toLocalDate(),
            rs.getBigDecimal("sugar_target_g_per_l"), rs.getString("malolactic_intent"),
            rs.getString("malolactic_strategy"), rs.getString("bacteria"),
            rs.getDate("malolactic_expected_at") == null ? null : rs.getDate("malolactic_expected_at").toLocalDate(),
            rs.getBigDecimal("temperature_min_celsius"), rs.getBigDecimal("temperature_max_celsius"),
            rs.getString("sampling_panel"), (Integer) rs.getObject("sampling_frequency_days"),
            rs.getString("author"), rs.getString("reason"), rs.getTimestamp("effective_at").toInstant());
    }

    private void validate(PlanVersionRequest request) {
        if (!INTENTS.contains(request.malolacticIntent())) {
            throw new BusinessRuleException("Intención maloláctica no soportada.");
        }
        if (request.temperatureMinCelsius() != null && request.temperatureMaxCelsius() != null
            && request.temperatureMinCelsius().compareTo(request.temperatureMaxCelsius()) > 0) {
            throw new BusinessRuleException("La temperatura mínima supera a la máxima.");
        }
    }

    private UUID destinationId(String value) {
        if (value == null || value.isBlank()) return null;
        List<UUID> ids = jdbc.query("select id from destination where active = true "
                + "and (lower(code) = lower(?) or lower(name) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Valor de catálogo de destino no encontrado.");
        return ids.getFirst();
    }

    private UUID panelId(String value) {
        if (value == null || value.isBlank()) return null;
        List<UUID> ids = jdbc.query("select id from analysis_panel where lower(code) = lower(?) or lower(name) = lower(?)",
            (rs, index) -> rs.getObject(1, UUID.class), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Panel de muestreo no encontrado.");
        return ids.getFirst();
    }

    private ContentScope content(String code) {
        UUID centerId = context.centerId();
        List<ContentScope> matches = jdbc.query("select cu.id, cu.code, cu.active from content_unit cu "
                + "join lot l on l.id = cu.lot_id where l.center_id = ? and cu.code = ?",
            (rs, index) -> new ContentScope(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getBoolean("active")), centerId, normalize(code));
        if (matches.isEmpty()) throw new NotFoundException("Unidad de contenido no encontrada.");
        return matches.getFirst();
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record PlanRow(UUID id, String name, String destination, int currentVersion) {}
    private record ContentScope(UUID id, String code, boolean active) {}
}
