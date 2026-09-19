package coop.miriv.enology.identity.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.CenterImpactResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a center together with everything that hangs from it (zones, deposits, lots, contents,
 * movements, samples and analyses, incidents, tasks, operations, plans) — irreversible, so it is
 * reserved to SUPER_ADMIN and requires the center code typed back as confirmation.
 *
 * <p>The audit trail is kept (it has no foreign keys) and records the purge with the counts.
 * Users whose only center is this one are deleted; if they still appear as author of data in
 * another center they are deactivated instead, without roles, so history elsewhere stays intact.
 */
@Service
public class CenterPurgeService {

    /** Label shown in the preview → temp table holding the ids that will be deleted. */
    private static final Map<String, String> COUNTED = new LinkedHashMap<>();
    static {
        COUNTED.put("Zonas", "p_zone");
        COUNTED.put("Depósitos", "p_dep");
        COUNTED.put("Lotes", "p_lot");
        COUNTED.put("Contenidos", "p_cu");
        COUNTED.put("Movimientos", "p_mov");
        COUNTED.put("Muestras", "p_sample");
        COUNTED.put("Análisis", "p_analysis");
        COUNTED.put("Incidencias", "p_inc");
        COUNTED.put("Tareas", "p_task");
        COUNTED.put("Operaciones", "p_op");
        COUNTED.put("Planes de elaboración", "p_plan");
        COUNTED.put("Laboratorios", "p_lab");
    }

    private final JdbcTemplate jdbc;
    private final UserAccountService accounts;
    private final CurrentUserContext context;
    private final AuditService audit;

    public CenterPurgeService(JdbcTemplate jdbc, UserAccountService accounts, CurrentUserContext context, AuditService audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.context = context;
        this.audit = audit;
    }

    @Transactional
    public CenterImpactResponse impact(String code) {
        UUID centerId = centerId(code);
        collect(centerId);
        return new CenterImpactResponse(code, counts(), usernames(), superAdminsMoved(), fallbackCenterName(centerId),
            accounts.actorIsSuperAdmin());
    }

    @Transactional
    public CenterImpactResponse purge(String code, String confirmation) {
        if (!accounts.actorIsSuperAdmin()) {
            throw new AccessDeniedException("Solo un superadministrador puede eliminar un centro con todo su contenido.");
        }
        if (confirmation == null || !confirmation.trim().equalsIgnoreCase(code)) {
            throw new BusinessRuleException("Para confirmar escribe exactamente el código del centro: " + code + ".");
        }
        UUID centerId = centerId(code);
        collect(centerId);
        UUID fallback = fallbackCenter(centerId);
        if (fallback == null) {
            throw new BusinessRuleException("Es el último centro: crea otro antes de eliminarlo.");
        }
        CenterImpactResponse summary = new CenterImpactResponse(code, counts(), usernames(), superAdminsMoved(),
            fallbackCenterName(centerId), true);
        // Super administrators are not tied to a center: they move to another one instead of being removed.
        jdbc.update("insert into app_user_center (user_id, center_id) select id, ? from p_super "
            + "on conflict do nothing", fallback);
        jdbc.update("update app_user set center_id = ? where id in (select id from p_super) and center_id = ?", fallback, centerId);

        String[] statements = {
            "delete from task_execution where task_id in (select id from p_task) or sample_id in (select id from p_sample)",
            "delete from task where id in (select id from p_task)",
            "delete from incident_evidence where incident_id in (select id from p_inc) or sample_id in (select id from p_sample) "
                + "or result_id in (select id from p_result)",
            "delete from incident_event where incident_id in (select id from p_inc)",
            "delete from incident where id in (select id from p_inc)",
            "update result set supersedes_result_id = null where supersedes_result_id in (select id from p_result)",
            "delete from result where id in (select id from p_result)",
            "delete from analysis where id in (select id from p_analysis)",
            "delete from sample where id in (select id from p_sample)",
            "delete from operation_addition where operation_id in (select id from p_op)",
            "delete from operation where id in (select id from p_op)",
            "update elaboration_plan set current_version_id = null where id in (select id from p_plan)",
            "delete from plan_exception where plan_version_id in (select id from p_pv) or content_unit_id in (select id from p_cu)",
            "delete from plan_phase_criterion where plan_version_id in (select id from p_pv)",
            "delete from plan_version where id in (select id from p_pv)",
            "delete from elaboration_plan where id in (select id from p_plan)",
            "delete from fermentation_state_review where content_unit_id in (select id from p_cu)",
            "delete from fermentation_state where content_unit_id in (select id from p_cu)",
            "delete from content_unit_lineage where content_unit_id in (select id from p_cu) "
                + "or parent_content_unit_id in (select id from p_cu) or movement_id in (select id from p_mov)",
            "delete from movement_line where movement_id in (select id from p_mov)",
            "delete from movement where id in (select id from p_mov)",
            "delete from occupation where content_unit_id in (select id from p_cu) or deposit_id in (select id from p_dep)",
            "delete from content_unit where id in (select id from p_cu)",
            "delete from lot_origin_line where lot_id in (select id from p_lot)",
            "delete from lot_variety where lot_id in (select id from p_lot)",
            "delete from lot where id in (select id from p_lot)",
            "delete from deposit_capacity_adjustment where deposit_id in (select id from p_dep)",
            "delete from deposit_cleaning_record where deposit_id in (select id from p_dep)",
            "delete from deposit where id in (select id from p_dep)",
            "delete from app_user_role where zone_id in (select id from p_zone)",
            "delete from app_user_permission_grant where zone_id in (select id from p_zone)",
            "delete from zone where id in (select id from p_zone)",
            "delete from laboratory where id in (select id from p_lab)",
        };
        for (String sql : statements) jdbc.update(sql);

        List<String> deactivated = removeUsers(centerId);
        jdbc.update("delete from app_user_center where center_id = ?", centerId);
        jdbc.update("delete from center where id = ?", centerId);

        audit.record("center", centerId, "CENTER_PURGED", "Centro " + code + " eliminado con todo su contenido", null, Map.of(
            "code", code, "deleted", summary.counts(), "users", summary.usersDeleted(), "usersDeactivated", deactivated,
            "superAdminsMoved", summary.superAdminsMoved()));
        return summary;
    }

    /** Users with other centers just lose this one; users with no other center are removed. */
    private List<String> removeUsers(UUID centerId) {
        // user_dashboard rows go with the user (ON DELETE CASCADE).
        // Users that keep other centers: move their primary center if it was this one.
        jdbc.update("""
            update app_user u set center_id = (select uc.center_id from app_user_center uc
                                                where uc.user_id = u.id and uc.center_id <> ? limit 1)
             where u.center_id = ? and u.id not in (select id from p_user)
            """, centerId, centerId);

        // Optional references to a removed user are cleared; mandatory ones mean "author of data elsewhere".
        String[][] nullable = {
            {"analysis", "validated_by_id"}, {"app_user_permission_grant", "granted_by_id"}, {"audit_log", "author_id"},
            {"deposit_cleaning_record", "responsible_id"}, {"fermentation_state", "confirmed_by_id"},
            {"incident", "resolved_by_id"}, {"incident", "responsible_id"}, {"incident_event", "created_by_id"},
            {"result", "validated_by_id"}, {"rule_version", "approved_by_id"}, {"task", "responsible_id"},
        };
        String[][] mandatory = {
            {"deposit_capacity_adjustment", "author_id"}, {"elaboration_plan", "responsible_id"},
            {"fermentation_state_review", "reviewed_by_id"}, {"lot", "responsible_id"}, {"movement", "responsible_id"},
            {"operation", "responsible_id"}, {"plan_exception", "responsible_id"}, {"plan_version", "author_id"},
            {"result", "created_by_id"}, {"sample", "taken_by_id"}, {"task_execution", "recorded_by_id"},
        };
        StringBuilder stillReferenced = new StringBuilder("select id from p_user where false");
        for (String[] ref : mandatory) {
            stillReferenced.append(" or id in (select ").append(ref[1]).append(" from ").append(ref[0]).append(")");
        }
        List<UUID> keep = jdbc.queryForList(stillReferenced.toString().replace("where false or", "where"), UUID.class);
        List<String> deactivated = keep.isEmpty() ? List.of() : jdbc.queryForList(
            "select username from app_user where id = any(?)", String.class, (Object) keep.toArray(UUID[]::new));

        jdbc.update("delete from app_user_role where user_id in (select id from p_user)");
        jdbc.update("delete from app_user_permission_grant where user_id in (select id from p_user)");
        jdbc.update("update app_user set active = false, deactivated_at = now(), center_id = null "
            + "where id in (select id from p_user)");
        jdbc.update("delete from p_user where id = any(?)", (Object) keep.toArray(UUID[]::new));
        for (String[] ref : nullable) {
            jdbc.update("update " + ref[0] + " set " + ref[1] + " = null where " + ref[1] + " in (select id from p_user)");
        }
        jdbc.update("delete from login_attempt where lower(username) in (select lower(username) from app_user where id in (select id from p_user))");
        jdbc.update("delete from app_user where id in (select id from p_user)");
        return deactivated;
    }

    private UUID centerId(String code) {
        return jdbc.query("select id from center where code = ?", (rs, n) -> rs.getObject(1, UUID.class), code)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Centro no encontrado."));
    }

    /** Builds the id sets (temporary, dropped at commit) of everything that belongs to the center. */
    private void collect(UUID c) {
        String[] temp = {
            "create temp table p_zone on commit drop as select id from zone where center_id = '%1$s'",
            "create temp table p_dep on commit drop as select id from deposit where center_id = '%1$s'",
            "create temp table p_lot on commit drop as select id from lot where center_id = '%1$s'",
            "create temp table p_lab on commit drop as select id from laboratory where center_id = '%1$s'",
            "create temp table p_cu on commit drop as select id from content_unit where lot_id in (select id from p_lot) "
                + "union select content_unit_id from occupation where deposit_id in (select id from p_dep)",
            "create temp table p_mov on commit drop as select movement_id as id from movement_line "
                + "where source_deposit_id in (select id from p_dep) or destination_deposit_id in (select id from p_dep) "
                + "or source_content_unit_id in (select id from p_cu) or destination_content_unit_id in (select id from p_cu) "
                + "union select movement_id from content_unit_lineage where content_unit_id in (select id from p_cu) "
                + "or parent_content_unit_id in (select id from p_cu)",
            "create temp table p_sample on commit drop as select id from sample where content_unit_id in (select id from p_cu) "
                + "or deposit_id_at_sampling in (select id from p_dep)",
            "create temp table p_analysis on commit drop as select id from analysis where sample_id in (select id from p_sample)",
            "create temp table p_result on commit drop as select id from result where analysis_id in (select id from p_analysis)",
            "create temp table p_inc on commit drop as select id from incident where content_unit_id in (select id from p_cu) "
                + "or deposit_id in (select id from p_dep)",
            "create temp table p_plan on commit drop as select id from elaboration_plan where content_unit_id in (select id from p_cu)",
            "create temp table p_pv on commit drop as select id from plan_version where plan_id in (select id from p_plan)",
            "create temp table p_task on commit drop as select id from task where content_unit_id in (select id from p_cu) "
                + "or deposit_id in (select id from p_dep) or source_incident_id in (select id from p_inc) "
                + "or source_plan_version_id in (select id from p_pv)",
            "create temp table p_op on commit drop as select id from operation where content_unit_id in (select id from p_cu) "
                + "or deposit_id in (select id from p_dep)",
            // Users whose only center is this one (by membership or, lacking any, by primary center).
            "create temp table p_only on commit drop as select u.id from app_user u "
                + "where (u.center_id = '%1$s' or exists (select 1 from app_user_center uc where uc.user_id = u.id and uc.center_id = '%1$s')) "
                + "and not exists (select 1 from app_user_center uc where uc.user_id = u.id and uc.center_id <> '%1$s')",
            // Super administrators among them are moved to another center, never removed.
            "create temp table p_super on commit drop as select id from p_only where id in (select ur.user_id from app_user_role ur "
                + "join role r on r.id = ur.role_id where r.code = 'SUPER_ADMIN')",
            "create temp table p_user on commit drop as select id from p_only where id not in (select id from p_super)",
        };
        // Dropped first too: impact() and purge() may run inside the same outer transaction.
        jdbc.execute("drop table if exists p_zone, p_dep, p_lot, p_lab, p_cu, p_mov, p_sample, p_analysis, p_result, "
            + "p_inc, p_plan, p_pv, p_task, p_op, p_only, p_super, p_user");
        for (String sql : temp) jdbc.execute(sql.formatted(c));
    }

    private List<String> superAdminsMoved() {
        return jdbc.queryForList("select username from app_user where id in (select id from p_super) order by username", String.class);
    }

    /** Where super administrators go: first remaining center by name, or null if this is the last one. */
    private UUID fallbackCenter(UUID centerId) {
        return jdbc.query("select id from center where id <> ? order by name limit 1",
            (rs, n) -> rs.getObject(1, UUID.class), centerId).stream().findFirst().orElse(null);
    }

    private String fallbackCenterName(UUID centerId) {
        return jdbc.query("select name from center where id <> ? order by name limit 1",
            (rs, n) -> rs.getString(1), centerId).stream().findFirst().orElse(null);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        COUNTED.forEach((label, table) -> out.put(label, jdbc.queryForObject("select count(*) from " + table, Integer.class)));
        return out;
    }

    private List<String> usernames() {
        return jdbc.queryForList("select username from app_user where id in (select id from p_user) order by username", String.class);
    }
}
