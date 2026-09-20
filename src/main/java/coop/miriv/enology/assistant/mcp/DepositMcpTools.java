package coop.miriv.enology.assistant.mcp;

import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.dto.MovementSummaryResponse;
import coop.miriv.enology.cellar.service.ContentService;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.assistant.mcp.DepositStatusDto.DepositStatus;
import coop.miriv.enology.cellar.service.MovementService;
import coop.miriv.enology.common.dto.PageResponse;
import coop.miriv.enology.incident.dto.IncidentResponse;
import coop.miriv.enology.incident.service.IncidentService;
import coop.miriv.enology.task.dto.TaskResponse;
import coop.miriv.enology.task.service.TaskService;
import coop.miriv.enology.tracking.dto.TrackingDto;
import coop.miriv.enology.tracking.service.AlertService;
import coop.miriv.enology.tracking.service.TrackingService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tools over the deposit domain. Every call delegates to the existing services, so the
 * authenticated user's center and readable zones are enforced exactly as in the REST API.
 */
@Component
public class DepositMcpTools {

    private static final int MAX_LIST = 60;

    private final DepositService deposits;
    private final ContentService contents;
    private final TrackingService tracking;
    private final AlertService alerts;
    private final IncidentService incidents;
    private final TaskService tasks;
    private final MovementService movements;
    private final DepositStatusService status;

    public DepositMcpTools(DepositService deposits, ContentService contents, TrackingService tracking,
                           AlertService alerts, IncidentService incidents, TaskService tasks,
                           MovementService movements, DepositStatusService status) {
        this.deposits = deposits;
        this.contents = contents;
        this.tracking = tracking;
        this.alerts = alerts;
        this.incidents = incidents;
        this.tasks = tasks;
        this.movements = movements;
        this.status = status;
    }

    @Tool(name = "get_deposit_status", description = "How a deposit is doing, in one call: what it holds, "
        + "every measured parameter with its trend over the last N days (default 30) — latest value, change, "
        + "change per day, target range and status OK/WARNING/CRITICAL, plus the individual readings — and its "
        + "active alerts, open incidents, pending tasks and recent winemaking events. Use this FIRST for any "
        + "question about how a deposit is, how it evolves or what to do with it.")
    public DepositStatus getDepositStatus(
            @ToolParam(description = "Deposit code, e.g. D-01") String code,
            @ToolParam(required = false, description = "Days of history to include (default 30, max 365)") Integer days) {
        return status.status(code, days);
    }

    @Tool(name = "list_deposits", description = "Lists the deposits (tanks) of the user's cellar with status, "
        + "capacity and active contents. Optional filters by zone and status. For one deposit's detail and "
        + "trends use get_deposit_status.")
    public List<DepositResponse> listDeposits(
            @ToolParam(required = false, description = "Zone code to filter by") String zone,
            @ToolParam(required = false, description = "Status: AVAILABLE, OCCUPIED, PENDING_CLEANING, CLEANING, MAINTENANCE") String status) {
        return deposits.list().stream()
            .filter(d -> blank(zone) || zone.equalsIgnoreCase(d.zone()))
            .filter(d -> blank(status) || status.equalsIgnoreCase(d.status()))
            .limit(MAX_LIST)
            .toList();
    }

    @Tool(name = "get_deposit", description = "Raw detail of one deposit: occupation history, lots and cleaning records. For how the deposit is doing use get_deposit_status instead.")
    public DepositResponse getDeposit(@ToolParam(description = "Deposit code, e.g. D-01") String code) {
        return deposits.get(code);
    }

    @Tool(name = "get_content", description = "Detail of a content unit (wine batch) by its content code.")
    public Object getContent(@ToolParam(description = "Content code") String contentCode) {
        return contents.get(contentCode);
    }

    @Tool(name = "get_deposit_latest_analysis", description = "Only the latest laboratory values of the wine in a deposit, without history. Prefer get_deposit_status, which already includes them with their trend.")
    public Object getDepositLatestAnalysis(@ToolParam(description = "Deposit code") String code) {
        List<String> active = activeContentCodes(code);
        return active.isEmpty() ? List.of() : tracking.latest(active);
    }

    @Tool(name = "get_deposit_series", description = "Time series of analytical parameters (density, temperature, pH, ...) for the wine in a deposit. Use list_parameters for valid codes.")
    public Object getDepositSeries(
            @ToolParam(description = "Deposit code") String code,
            @ToolParam(description = "Parameter codes") List<String> parameters,
            @ToolParam(required = false, description = "ISO-8601 instant, start") String from,
            @ToolParam(required = false, description = "ISO-8601 instant, end") String to) {
        List<String> active = activeContentCodes(code);
        if (active.isEmpty()) return List.of();
        return tracking.series(active, parameters, instant(from), instant(to), false);
    }

    @Tool(name = "get_deposit_events", description = "Winemaking events (additions, racking, treatments) of the wine in a deposit.")
    public Object getDepositEvents(
            @ToolParam(description = "Deposit code") String code,
            @ToolParam(required = false, description = "ISO-8601 instant, start") String from,
            @ToolParam(required = false, description = "ISO-8601 instant, end") String to) {
        List<String> active = activeContentCodes(code);
        return active.isEmpty() ? List.of() : tracking.events(active, instant(from), instant(to));
    }

    @Tool(name = "list_parameters", description = "Catalogue of analytical parameters that can be queried.")
    public Object listParameters() {
        return tracking.parameters(false);
    }

    @Tool(name = "get_alerts", description = "Active alerts of the cellar. Optionally restricted to one deposit.")
    public List<TrackingDto.AlertView> getAlerts(@ToolParam(required = false, description = "Deposit code") String deposit) {
        return alerts.alerts().stream()
            .filter(a -> blank(deposit) || deposit.equalsIgnoreCase(a.deposit()))
            .limit(MAX_LIST)
            .toList();
    }

    @Tool(name = "get_deposit_targets_and_rules", description = "Target ranges and alert rules configured for the wine in a deposit.")
    public Object getDepositAlertRules(@ToolParam(description = "Deposit code") String code) {
        return activeContentCodes(code).stream().map(alerts::rulesForContent).toList();
    }

    @Tool(name = "get_cellar_overview", description = "Cellar-wide overview of all active contents with their latest values.")
    public Object getCellarOverview() {
        return tracking.overview(null);
    }

    @Tool(name = "list_incidents", description = "Incidents of the cellar; filter by deposit and/or only open ones.")
    public List<IncidentResponse> listIncidents(
            @ToolParam(required = false, description = "Deposit code") String deposit,
            @ToolParam(required = false, description = "Only open incidents") Boolean onlyOpen) {
        return incidents.list().stream()
            .filter(i -> blank(deposit) || deposit.equalsIgnoreCase(i.depositCode()))
            .filter(i -> !Boolean.TRUE.equals(onlyOpen) || !"CLOSED".equalsIgnoreCase(i.status()))
            .limit(MAX_LIST)
            .toList();
    }

    @Tool(name = "list_tasks", description = "Tasks of the cellar; filter by deposit and/or only pending ones.")
    public List<TaskResponse> listTasks(
            @ToolParam(required = false, description = "Deposit code") String deposit,
            @ToolParam(required = false, description = "Only pending/in-progress tasks") Boolean onlyPending) {
        return tasks.list().stream()
            .filter(t -> blank(deposit) || deposit.equalsIgnoreCase(t.depositCode()))
            .filter(t -> !Boolean.TRUE.equals(onlyPending)
                || !(Objects.toString(t.status(), "").matches("(?i)COMPLETED|DONE|CANCELLED|CANCELED")))
            .limit(MAX_LIST)
            .toList();
    }

    @Tool(name = "list_movements", description = "Recent movements (transfers, racking) involving a deposit.")
    public PageResponse<MovementSummaryResponse> listMovements(
            @ToolParam(description = "Deposit code") String deposit,
            @ToolParam(required = false, description = "yyyy-MM-dd") String from,
            @ToolParam(required = false, description = "yyyy-MM-dd") String to) {
        return movements.list(blank(from) ? null : LocalDate.parse(from), blank(to) ? null : LocalDate.parse(to),
            deposit, null, null, null, null, null, 0, 20);
    }

    private List<String> activeContentCodes(String depositCode) {
        return deposits.get(depositCode).occupations().stream()
            .filter(o -> o.exitDate() == null)
            .map(o -> o.contentCode())
            .toList();
    }

    private static Instant instant(String value) { return blank(value) ? null : Instant.parse(value); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
