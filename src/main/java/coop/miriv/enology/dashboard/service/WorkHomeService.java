package coop.miriv.enology.dashboard.service;

import coop.miriv.enology.dashboard.dto.AttentionItem;
import coop.miriv.enology.dashboard.dto.DashboardMetric;
import coop.miriv.enology.dashboard.dto.OwnTasks;
import coop.miriv.enology.dashboard.dto.RecentActivity;
import coop.miriv.enology.dashboard.dto.WorkHomeResponse;
import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.repository.AppUserRepository;
import coop.miriv.enology.identity.service.CurrentUserProvider;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkHomeService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;
    private final ZoneId timezone;

    public WorkHomeService(JdbcTemplate jdbc, AppUserRepository users, CurrentUserProvider currentUser,
                           @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.users = users;
        this.currentUser = currentUser;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public WorkHomeResponse get() {
        AppUser user = users.findById(currentUser.requireCurrentUserId()).filter(AppUser::isActive)
            .orElseThrow(() -> new AccessDeniedException("Current user is not active."));
        if (user.getCenter() == null) throw new AccessDeniedException("Current user has no assigned center.");
        UUID centerId = user.getCenter().getId();
        int urgent = count("select count(*) from incident i join deposit d on d.id = i.deposit_id "
            + "where d.center_id = ? and i.priority = 'URGENT'::alert_priority "
            + "and i.status not in ('RESOLVED'::incident_status, 'DISCARDED'::incident_status)", centerId);
        int attention = count("select count(*) from incident i join deposit d on d.id = i.deposit_id "
            + "where d.center_id = ? and i.status not in ('RESOLVED'::incident_status, 'DISCARDED'::incident_status)", centerId);
        int overdue = count("select count(*) from task t join deposit d on d.id = t.deposit_id "
            + "where d.center_id = ? and t.due_at < now() "
            + "and t.status in ('PENDING'::task_status, 'IN_PROGRESS'::task_status)", centerId);
        int pendingValidation = count("select count(*) from analysis a join sample s on s.id = a.sample_id "
            + "join deposit d on d.id = s.deposit_id_at_sampling "
            + "where d.center_id = ? and a.status = 'PENDING_VALIDATION'::analysis_status", centerId);
        int ownTaskCount = jdbc.queryForObject("select count(*) from task where responsible_id = ? "
                + "and status in ('PENDING'::task_status, 'IN_PROGRESS'::task_status)", Integer.class, user.getId());
        List<DashboardMetric> metrics = List.of(
            new DashboardMetric("Urgent incidents", Integer.toString(urgent), "Open incidents", "error"),
            new DashboardMetric("Overdue controls", Integer.toString(overdue), "Open overdue tasks", "warning"),
            new DashboardMetric("Pending validations", Integer.toString(pendingValidation), "Laboratory queue", "default"));
        List<AttentionItem> items = jdbc.query("select i.code, d.code as deposit_code, i.priority::text, "
                + "coalesce(cat.name, 'Unclassified') as category, coalesce(l.code, '') as lot_code, "
                + "i.title, i.opened_at from incident i join deposit d on d.id = i.deposit_id "
                + "left join content_unit cu on cu.id = i.content_unit_id "
                + "left join lot l on l.id = cu.lot_id "
                + "left join internal_category cat on cat.id = cu.category_id "
                + "where d.center_id = ? and i.status not in ('RESOLVED'::incident_status, 'DISCARDED'::incident_status) "
                + "order by case i.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end, i.opened_at limit 20",
            (rs, index) -> {
                String priority = rs.getString("priority");
                return new AttentionItem(rs.getString("code"), rs.getString("deposit_code"),
                    priority.equals("URGENT") ? "critical" : "high", priority,
                    rs.getString("category"), rs.getString("lot_code"), rs.getString("title"),
                    null, null, "Open incident", rs.getTimestamp("opened_at").toInstant().toString(),
                    "View evidence");
            }, centerId);
        List<RecentActivity> activity = jdbc.query("select m.effective_at, m.code, m.type::text as type "
                + "from movement m join movement_line ml on ml.movement_id = m.id "
                + "left join deposit source on source.id = ml.source_deposit_id "
                + "left join deposit destination on destination.id = ml.destination_deposit_id "
                + "where source.center_id = ? or destination.center_id = ? "
                + "group by m.id order by m.effective_at desc limit 8",
            (rs, index) -> new RecentActivity(
                rs.getTimestamp("effective_at").toInstant().atZone(timezone).format(DateTimeFormatter.ofPattern("HH:mm")),
                rs.getString("type") + " " + rs.getString("code")), centerId, centerId);
        String updatedAt = Instant.now().atZone(timezone).format(DateTimeFormatter.ofPattern("HH:mm"));
        return new WorkHomeResponse(user.getCenter().getName(),
            "Campaign " + java.time.LocalDate.now(timezone).getYear(), updatedAt, urgent, attention,
            metrics, items, new OwnTasks("available", ownTaskCount, ownTaskCount + " open tasks"), activity);
    }

    private int count(String sql, UUID centerId) { return jdbc.queryForObject(sql, Integer.class, centerId); }
}
