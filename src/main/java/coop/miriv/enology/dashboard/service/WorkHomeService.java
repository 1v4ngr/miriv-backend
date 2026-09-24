package coop.miriv.enology.dashboard.service;

import coop.miriv.enology.dashboard.dto.DashboardMetric;
import coop.miriv.enology.dashboard.dto.DayCount;
import coop.miriv.enology.dashboard.dto.RecentActivity;
import coop.miriv.enology.dashboard.dto.WorkHomeResponse;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkHomeService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ZoneId timezone;

    public WorkHomeService(JdbcTemplate jdbc, CurrentUserContext context,
                           @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public WorkHomeResponse get() {
        UUID centerId = context.centerId();
        coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter depositFilter = context.readZoneFilter("d");
        String depositZoneSql = depositFilter.allZones() ? "" : " and (d.zone_id is null or d.zone_id in ("
            + (depositFilter.zoneIds().isEmpty() ? "select null::uuid where false"
                : String.join(",", depositFilter.zoneIds().stream().map(id -> "?").toList())) + "))";
        Object[] depositZoneParams = depositFilter.allZones() || depositFilter.zoneIds().isEmpty()
            ? new Object[]{centerId} : prepend(centerId, depositFilter.zoneIds());
        int pendingValidation = count("select count(*) from analysis a join sample s on s.id = a.sample_id "
            + "join deposit d on d.id = s.deposit_id_at_sampling "
            + "where d.center_id = ?" + depositZoneSql
            + " and a.status = 'PENDING_VALIDATION'::analysis_status", depositZoneParams);
        // F2-03: metric labels are now stable codes on the wire; the front maps them via labels.ts.
        List<DashboardMetric> metrics = List.of(
            new DashboardMetric("PENDING_VALIDATIONS", Integer.toString(pendingValidation), null, "default"));
        List<RecentActivity> activity = jdbc.query("select m.effective_at, m.code, m.type::text as type, "
                + "string_agg(distinct source.code, ', ') as source, string_agg(distinct destination.code, ', ') as destination, "
                + "sum(ml.volume_liters) as liters, min(l.code) as lot "
                + "from movement m join movement_line ml on ml.movement_id = m.id "
                + "left join deposit source on source.id = ml.source_deposit_id "
                + "left join deposit destination on destination.id = ml.destination_deposit_id "
                + "left join content_unit cu on cu.id = coalesce(ml.destination_content_unit_id, ml.source_content_unit_id) "
                + "left join lot l on l.id = cu.lot_id "
                + "where (source.center_id = ? or destination.center_id = ?) and m.status::text = 'EXECUTED' "
                + "group by m.id order by m.effective_at desc limit 8",
            (rs, index) -> {
                Instant at = rs.getTimestamp("effective_at").toInstant();
                return new RecentActivity(at.atZone(timezone).format(DateTimeFormatter.ofPattern("HH:mm")),
                    rs.getString("type") + " " + rs.getString("code"), at, rs.getString("type"), rs.getString("code"),
                    rs.getString("source"), rs.getString("destination"), rs.getBigDecimal("liters"), rs.getString("lot"));
            }, centerId, centerId);
        String updatedAt = Instant.now().atZone(timezone).format(DateTimeFormatter.ofPattern("HH:mm"));
        // F2-03: campaign is just the year; the front renders "Campaña 2026".
        return new WorkHomeResponse(context.center().getName(),
            Integer.toString(java.time.LocalDate.now(timezone).getYear()), updatedAt,
            metrics, activity, samplesPerDay(depositZoneSql, depositZoneParams));
    }

    /** Samples taken per day in the deposits the user can see, last 30 days including today, zero-filled. */
    private List<DayCount> samplesPerDay(String depositZoneSql, Object[] depositZoneParams) {
        LocalDate today = LocalDate.now(timezone);
        LocalDate from = today.minusDays(29);
        Map<LocalDate, Integer> counts = new HashMap<>();
        List<Object> params = new ArrayList<>(List.of(timezone.getId()));
        params.addAll(List.of(depositZoneParams));
        params.add(from.atStartOfDay(timezone).toInstant().atOffset(java.time.ZoneOffset.UTC));
        jdbc.query("select (s.taken_at at time zone ?)::date as day, count(*) from sample s "
                + "join deposit d on d.id = s.deposit_id_at_sampling where d.center_id = ?" + depositZoneSql
                + " and s.taken_at >= ? group by 1",
            (RowCallbackHandler) rs -> counts.put(rs.getObject(1, LocalDate.class), rs.getInt(2)), params.toArray());
        List<DayCount> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new DayCount(day.toString(), counts.getOrDefault(day, 0)));
        }
        return days;
    }

    private int count(String sql, Object... params) { return jdbc.queryForObject(sql, Integer.class, params); }

    private static Object[] prepend(UUID centerId, java.util.List<?> extras) {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(centerId);
        params.addAll(extras);
        return params.toArray();
    }
}