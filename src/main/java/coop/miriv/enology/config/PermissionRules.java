package coop.miriv.enology.config;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Single table of URL → permission rules (plan §2A). Evaluated in order: put specific paths first.
 * Every new write endpoint MUST be added here, otherwise SecurityConfig denies it.
 */
public final class PermissionRules {

    public record Rule(HttpMethod method, String pattern, String... permissions) {}

    private static Rule rule(HttpMethod method, String pattern, String... permissions) {
        return new Rule(method, pattern, permissions);
    }

    static final HttpMethod GET = HttpMethod.GET, POST = HttpMethod.POST, PUT = HttpMethod.PUT,
        PATCH = HttpMethod.PATCH, DELETE = HttpMethod.DELETE;

    public static final List<Rule> RULES = List.of(
        // Administration (reads included)
        rule(GET, "/api/admin/audit/**", "AUDIT_READ"),
        rule(GET, "/api/audit/**", "AUDIT_READ"),
        rule(null, "/api/admin/users/**", "USER_MANAGE"),
        rule(null, "/api/admin/roles/**", "USER_MANAGE"),
        rule(null, "/api/admin/permissions/**", "USER_MANAGE"),
        rule(null, "/api/admin/centers/**", "ORG_MANAGE"),
        rule(null, "/api/admin/zones/**", "ORG_MANAGE"),
        rule(null, "/api/admin/laboratories/**", "ORG_MANAGE"),
        rule(null, "/api/admin/parameter-targets/**", "LAB_CATALOG_MANAGE"),
        rule(null, "/api/admin/alert-rules/**", "RULE_EDIT"),
        rule(POST, "/api/tracking/alerts/**", "INCIDENT_ACKNOWLEDGE"),
        rule(PUT, "/api/tracking/contents/**", "RULE_EDIT"),
        rule(DELETE, "/api/tracking/contents/**", "RULE_EDIT"),
        rule(POST, "/api/catalogs/parameters/**", "LAB_CATALOG_MANAGE"),
        rule(PUT, "/api/catalogs/parameters/**", "LAB_CATALOG_MANAGE"),
        rule(POST, "/api/catalogs/panels/**", "LAB_CATALOG_MANAGE"),
        rule(PUT, "/api/catalogs/panels/**", "LAB_CATALOG_MANAGE"),
        rule(POST, "/api/catalogs/**", "CATALOG_MANAGE"),
        rule(PUT, "/api/catalogs/**", "CATALOG_MANAGE"),
        rule(DELETE, "/api/catalogs/**", "CATALOG_MANAGE"),
        // Blend simulations: reads are open to any authenticated user; writes and conversion plan movements
        rule(POST, "/api/blends/**", "MOVEMENT_PLAN"),
        rule(PUT, "/api/blends/**", "MOVEMENT_PLAN"),
        rule(DELETE, "/api/blends/**", "MOVEMENT_PLAN"),
        // Cellar
        rule(POST, "/api/deposits/*/cleaning/**", "DEPOSIT_CLEANING"),
        rule(POST, "/api/deposits/**", "DEPOSIT_MANAGE"),
        rule(PATCH, "/api/deposits/**", "DEPOSIT_MANAGE"),
        rule(POST, "/api/lots/**", "LOT_MANAGE"),
        rule(PATCH, "/api/lots/**", "LOT_MANAGE"),
        rule(POST, "/api/movements/deposits/*/content-clearance", "CONTENT_CORRECT"),
        rule(POST, "/api/movements/*/cancel", "MOVEMENT_PLAN"),
        rule(PATCH, "/api/movements/*", "MOVEMENT_REGISTER"),
        // Undo also demands SUPER_ADMIN in the service.
        rule(DELETE, "/api/movements/*", "MOVEMENT_REGISTER"),
        rule(POST, "/api/movements/**", "MOVEMENT_REGISTER", "MOVEMENT_PLAN"),
        rule(POST, "/api/contents/*/state-reviews", "STATE_CONFIRM"),
        // Laboratory
        rule(POST, "/api/laboratory/samples/*/validate", "ANALYSIS_VALIDATE"),
        rule(POST, "/api/laboratory/samples/*/results/*/correction", "RESULT_CORRECT"),
        rule(POST, "/api/laboratory/samples/*/invalidate", "ANALYSIS_INVALIDATE"),
        // The service also demands SUPER_ADMIN; the rule keeps it out of everyone else's reach.
        rule(DELETE, "/api/laboratory/samples/*", "ANALYSIS_INVALIDATE"),
        rule(PUT, "/api/laboratory/samples/*/results", "RESULT_ENTER"),
        rule(PUT, "/api/laboratory/results/**", "RESULT_ENTER"),
        rule(POST, "/api/laboratory/samples/*/deposit", "SAMPLE_REASSIGN"),
        rule(POST, "/api/laboratory/samples", "SAMPLE_REGISTER"),
        rule(null, "/api/laboratory/imports/**", "RESULT_IMPORT"),
        // Elaboration
        rule(POST, "/api/plans/templates/*/versions/*/approve", "PLAN_APPROVE"),
        rule(POST, "/api/plans/contents/*/versions/*/approve", "PLAN_APPROVE"),
        rule(POST, "/api/plans/contents/*/exceptions", "PLAN_EXCEPTION"),
        rule(POST, "/api/plans/contents/*", "PLAN_APPROVE"),
        rule(POST, "/api/plans/**", "PLAN_EDIT"),
        rule(PUT, "/api/plans/**", "PLAN_EDIT"),
        rule(POST, "/api/rules/*/versions/*/approve", "RULE_APPROVE"),
        rule(POST, "/api/rules/*/activate", "RULE_APPROVE"),
        rule(POST, "/api/rules/*/deactivate", "RULE_APPROVE"),
        rule(POST, "/api/recommendations/*/versions/*/approve", "RULE_APPROVE"),
        rule(POST, "/api/rules/**", "RULE_EDIT"),
        rule(PUT, "/api/rules/**", "RULE_EDIT"),
        rule(POST, "/api/recommendations/**", "RULE_EDIT"),
        rule(PUT, "/api/recommendations/**", "RULE_EDIT"),
        // Operations
        rule(POST, "/api/operations/*/execute", "OPERATION_EXECUTE"),
        rule(POST, "/api/operations/**", "OPERATION_PLAN"),
        // Incidents
        rule(POST, "/api/incidents/*/acknowledge", "INCIDENT_ACKNOWLEDGE"),
        rule(POST, "/api/incidents/*/assign", "INCIDENT_ASSIGN"),
        rule(POST, "/api/incidents/*/silence", "INCIDENT_SILENCE"),
        rule(POST, "/api/incidents/*/unsilence", "INCIDENT_SILENCE"),
        rule(POST, "/api/incidents/*/tasks", "TASK_CREATE"),
        rule(POST, "/api/incidents/**", "INCIDENT_CLOSE"),
        // Tasks
        rule(POST, "/api/tasks/*/start", "TASK_EXECUTE_OWN", "TASK_EXECUTE_ANY"),
        rule(POST, "/api/tasks/*/complete", "TASK_EXECUTE_OWN", "TASK_EXECUTE_ANY"),
        rule(POST, "/api/tasks/*/cancel", "TASK_CANCEL"),
        rule(PATCH, "/api/tasks/*", "TASK_ASSIGN"),
        rule(POST, "/api/tasks", "TASK_CREATE"),
        // Reports
        rule(GET, "/api/reports/*/file", "REPORT_EXPORT"),
        rule(POST, "/api/reports/**", "REPORT_EXPORT")
    );

    private PermissionRules() {}
}