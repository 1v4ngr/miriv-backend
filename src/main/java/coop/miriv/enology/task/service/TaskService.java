package coop.miriv.enology.task.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.task.dto.CompleteTaskRequest;
import coop.miriv.enology.task.dto.CreateTaskRequest;
import coop.miriv.enology.task.dto.TaskResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private static final Set<String> PRIORITIES = Set.of("NONE", "LOW", "MEDIUM", "HIGH");

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;

    public TaskService(JdbcTemplate jdbc, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list() {
        return jdbc.query(TASK_SELECT + " where d.center_id = ?" + context.readZoneFilter("d").sql()
                + " order by t.due_at nulls last, t.created_at desc",
            (rs, index) -> response(rs), prependZoneFilter(context.centerId(), context.readZoneFilter("d")));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(String code) {
        return find(code, context.centerId());
    }

    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        UUID centerId = context.centerId();
        if (!PRIORITIES.contains(request.priority())) throw new BusinessRuleException("Unsupported task priority.");
        UUID depositId = depositId(request.depositCode(), centerId);
        context.requireInZone("TASK_CREATE", depositZone(depositId));
        UUID contentId = contentId(request.contentCode(), centerId);
        if (contentId != null) requireCurrentLocation(contentId, depositId);
        UUID responsibleId = responsibleId(request.responsible(), centerId);
        UUID id = UUID.randomUUID();
        String code = "TSK-" + java.time.LocalDate.now().getYear() + "-"
            + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("insert into task(id, code, title, deposit_id, content_unit_id, responsible_id, "
                + "due_at, priority, status, completion_criterion, description) values (?, ?, ?, ?, ?, ?, ?, "
                + "cast(? as task_priority), 'PENDING'::task_status, ?, ?)",
            id, code, request.title().trim(), depositId, contentId, responsibleId,
            Timestamp.from(request.dueAt()), request.priority(), blankToNull(request.completionCriterion()),
            blankToNull(request.description()));
        return get(code);
    }

    @Transactional
    public TaskResponse start(String code) {
        TaskLock task = lock(code);
        requireAssigneeOrManager(task);
        if (!task.status().equals("PENDING")) throw new BusinessRuleException("Only pending tasks can be started.");
        if (task.contentId() != null) requireCurrentLocation(task.contentId(), task.depositId());
        jdbc.update("update task set status = 'IN_PROGRESS'::task_status where id = ?", task.id());
        return get(code);
    }

    @Transactional
    public TaskResponse complete(String code, CompleteTaskRequest request) {
        TaskLock task = lock(code);
        requireAssigneeOrManager(task);
        if (!task.status().equals("PENDING") && !task.status().equals("IN_PROGRESS")) {
            throw new BusinessRuleException("Task is not open for completion.");
        }
        if (task.contentId() != null) requireCurrentLocation(task.contentId(), task.depositId());
        Instant executedAt = request.executedAt() == null ? Instant.now() : request.executedAt();
        if (executedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new BusinessRuleException("La fecha de ejecución no puede estar en el futuro.");
        }
        UUID sampleId = sampleId(request.sampleCode(), task.contentId(), context.centerId(),
            "ANALYSIS_REQUIRED".equals(task.completionCriterion()));
        if ("ANALYSIS_REQUIRED".equals(task.completionCriterion()) && sampleId == null) {
            throw new BusinessRuleException("A linked sample is required to complete this analytical task.");
        }
        jdbc.update("insert into task_execution(id, task_id, result, observations, sample_id, sample_point, executed_at, recorded_by_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), task.id(), request.result().trim(),
            blankToNull(request.observations()), sampleId, blankToNull(request.samplePoint()),
            Timestamp.from(executedAt), context.userId());
        jdbc.update("update task set status = 'DONE'::task_status where id = ?", task.id());
        return get(code);
    }

    @Transactional
    public TaskResponse cancel(String code, String reason) {
        TaskLock task = lock(code);
        if (task.status().equals("DONE") || task.status().equals("CANCELLED")) {
            throw new BusinessRuleException("Completed or cancelled tasks cannot be cancelled again.");
        }
        jdbc.update("update task set status = 'CANCELLED'::task_status, cancelled_reason = ? where id = ?",
            reason.trim(), task.id());
        return get(code);
    }

    private TaskResponse find(String code, UUID centerId) {
        List<TaskResponse> rows = jdbc.query(TASK_SELECT + " where d.center_id = ? and t.code = ?",
            (rs, index) -> response(rs), centerId, normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Task not found.");
        return rows.getFirst();
    }

    private TaskResponse response(ResultSet rs) throws SQLException {
        return new TaskResponse(rs.getString("code"), rs.getString("title"), rs.getString("deposit_code"),
            rs.getString("content_code"), rs.getString("responsible"),
            rs.getString("responsible_username"),
            rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
            rs.getString("priority"), rs.getString("status"), rs.getString("completion_criterion"),
            rs.getString("description"),
            rs.getString("sample_point"), rs.getString("sample_code"),
            rs.getTimestamp("executed_at") == null ? null : rs.getTimestamp("executed_at").toInstant(),
            rs.getString("result"), rs.getString("observations"));
    }

    private TaskLock lock(String code) {
        List<TaskLock> rows = jdbc.query("select t.id, t.deposit_id, t.content_unit_id, t.responsible_id, "
                + "t.status::text, t.completion_criterion from task t join deposit d on d.id = t.deposit_id "
                + "where d.center_id = ? and t.code = ? for update of t",
            (rs, index) -> new TaskLock(rs.getObject("id", UUID.class),
                rs.getObject("deposit_id", UUID.class), rs.getObject("content_unit_id", UUID.class),
                rs.getObject("responsible_id", UUID.class),
                rs.getString("status"), rs.getString("completion_criterion")), context.centerId(), normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Task not found.");
        return rows.getFirst();
    }

    private void requireCurrentLocation(UUID contentId, UUID depositId) {
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from occupation "
            + "where content_unit_id = ? and deposit_id = ? and end_at is null)", Boolean.class, contentId, depositId));
        if (!matches) throw new BusinessRuleException("Content has moved; review task applicability before executing.");
    }

    private UUID depositId(String code, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from deposit where center_id = ? and code = ? and active = true",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, normalize(code));
        if (ids.isEmpty()) throw new NotFoundException("Deposit not found.");
        return ids.getFirst();
    }

    private UUID depositZone(UUID depositId) {
        return jdbc.queryForObject("select zone_id from deposit where id = ?", UUID.class, depositId);
    }

    private static Object[] prependZoneFilter(UUID centerId, coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter filter) {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(centerId);
        params.addAll(filter.zoneIds());
        return params.toArray();
    }

    private UUID contentId(String code, UUID centerId) {
        if (code == null || code.isBlank()) return null;
        List<UUID> ids = jdbc.query("select cu.id from content_unit cu join lot l on l.id = cu.lot_id "
                + "where l.center_id = ? and cu.code = ?", (rs, index) -> rs.getObject(1, UUID.class),
            centerId, normalize(code));
        if (ids.isEmpty()) throw new NotFoundException("Content unit not found.");
        return ids.getFirst();
    }

    private UUID sampleId(String code, UUID contentId, UUID centerId, boolean requireValidated) {
        if (code == null || code.isBlank()) return null;
        String sql = "select s.id from sample s join deposit d on d.id = s.deposit_id_at_sampling "
            + "join analysis a on a.sample_id = s.id where d.center_id = ? and s.code = ? "
            + "and (?::uuid is null or s.content_unit_id = ?)";
        if (requireValidated) sql += " and a.status = 'VALIDATED'::analysis_status";
        List<UUID> ids = jdbc.query(sql, (rs, index) -> rs.getObject(1, UUID.class),
            centerId, normalize(code), contentId, contentId);
        if (ids.isEmpty()) throw new NotFoundException("Linked sample not found for this task content.");
        return ids.getFirst();
    }

    private UUID responsibleId(String value, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from app_user where center_id = ? and active = true "
                + "and (lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsable no encontrado en el centro actual: " + value);
        return ids.getFirst();
    }

    private void requireAssigneeOrManager(TaskLock task) {
        AppUser user = context.user();
        if (!user.getId().equals(task.responsibleId()) && !context.has("TASK_EXECUTE_ANY")) {
            throw new AccessDeniedException("Task is assigned to another user.");
        }
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static final String TASK_SELECT = "select t.code, t.title, d.code as deposit_code, "
        + "cu.code as content_code, u.full_name as responsible, u.username as responsible_username, t.due_at, t.priority::text, "
        + "t.status::text, t.completion_criterion, t.description, execution.executed_at, execution.result, "
        + "execution.observations, execution.sample_point, execution.sample_code "
        + "from task t join deposit d on d.id = t.deposit_id "
        + "left join content_unit cu on cu.id = t.content_unit_id "
        + "left join app_user u on u.id = t.responsible_id "
        + "left join lateral (select te.executed_at, te.result, te.observations, te.sample_point, s.code as sample_code "
        + "from task_execution te left join sample s on s.id = te.sample_id "
        + "where te.task_id = t.id order by te.executed_at desc limit 1) execution on true";

    private record TaskLock(UUID id, UUID depositId, UUID contentId, UUID responsibleId,
                            String status, String completionCriterion) {}
}
