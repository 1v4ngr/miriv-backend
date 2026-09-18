package coop.miriv.enology.incident.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.incident.dto.IncidentEventResponse;
import coop.miriv.enology.incident.dto.IncidentResponse;
import coop.miriv.enology.incident.dto.ResolveIncidentRequest;
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
public class IncidentService {

    private static final Set<String> DISCARD_CATEGORIES = Set.of("DATA_ERROR", "FALSE_POSITIVE", "EXPECTED_CONDITION");

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;

    public IncidentService(JdbcTemplate jdbc, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list() {
        coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter filter = context.readZoneFilter("d");
        String filterSql = filter.allZones() ? "" : " and (d.zone_id is null or d.zone_id in ("
            + (filter.zoneIds().isEmpty() ? "select null::uuid where false" : String.join(",", filter.zoneIds().stream().map(id -> "?").toList())) + "))";
        return jdbc.query(INCIDENT_SELECT + " where coalesce(d.center_id, l.center_id) = ?"
                + filterSql + " order by case i.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end, i.opened_at desc",
            (rs, index) -> response(rs), prependZoneFilter());
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(String code) {
        List<IncidentResponse> rows = jdbc.query(INCIDENT_SELECT
                + " where coalesce(d.center_id, l.center_id) = ? and i.code = ?",
            (rs, index) -> response(rs), context.centerId(), normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Incident not found.");
        return rows.getFirst();
    }

    @Transactional
    public IncidentResponse acknowledge(String code) {
        LockedIncident incident = lock(code);
        requireOpen(incident);
        if (incident.status().equals("NEW") || incident.status().equals("ASSIGNED")) {
            jdbc.update("update incident set status = 'IN_REVIEW'::incident_status where id = ?", incident.id());
        }
        event(incident.id(), "ACKNOWLEDGED", "Incident reviewed by user.");
        return get(code);
    }

    @Transactional
    public IncidentResponse assign(String code, String responsible) {
        LockedIncident incident = lock(code);
        requireOpen(incident);
        UUID responsibleId = responsibleId(responsible, context.centerId());
        jdbc.update("update incident set responsible_id = ?, status = 'ASSIGNED'::incident_status where id = ?",
            responsibleId, incident.id());
        event(incident.id(), "ASSIGNED", "Assigned to " + responsible.trim());
        return get(code);
    }

    @Transactional
    public IncidentResponse silence(String code, Instant until, String reason) {
        LockedIncident incident = lock(code);
        requireOpen(incident);
        if (!until.isAfter(Instant.now())) throw new BusinessRuleException("Silence end must be in the future.");
        jdbc.update("update incident set silenced_until = ? where id = ?", Timestamp.from(until), incident.id());
        event(incident.id(), "SILENCED", reason.trim() + " until " + until);
        return get(code);
    }

    @Transactional
    public IncidentResponse close(String code, ResolveIncidentRequest request, boolean discard) {
        LockedIncident incident = lock(code);
        requireOpen(incident);
        String reason = request.reason().trim();
        if (discard) {
            if (!DISCARD_CATEGORIES.contains(request.discardCategory())) {
                throw new BusinessRuleException("Discard category must identify a data error, false positive or expected condition.");
            }
            reason = request.discardCategory() + ": " + reason;
        }
        String outcome = discard ? "DISCARDED" : "RESOLVED";
        jdbc.update("update incident set status = cast(? as incident_status), resolution = cast(? as incident_resolution), "
                + "resolution_reason = ?, resolved_at = now(), resolved_by_id = ?, silenced_until = null where id = ?",
            outcome, outcome, reason, context.userId(), incident.id());
        event(incident.id(), outcome, reason);
        return get(code);
    }

    private IncidentResponse response(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<IncidentEventResponse> events = jdbc.query("select e.event_type, e.note, u.full_name, e.created_at "
                + "from incident_event e left join app_user u on u.id = e.created_by_id "
                + "where e.incident_id = ? order by e.created_at",
            (history, index) -> new IncidentEventResponse(history.getString("event_type"),
                history.getString("note"), history.getString("full_name"),
                history.getTimestamp("created_at").toInstant()), id);
        List<String> evidence = jdbc.query("select note from incident_evidence where incident_id = ? "
                + "and superseded = false order by recorded_at", (e, index) -> e.getString(1), id);
        return new IncidentResponse(rs.getString("code"), rs.getString("title"), rs.getString("deposit_code"),
            rs.getString("content_code"), rs.getString("priority"), rs.getString("status"),
            rs.getString("responsible"), rs.getString("responsible_username"),
            rs.getTimestamp("opened_at").toInstant(),
            rs.getTimestamp("silenced_until") == null ? null : rs.getTimestamp("silenced_until").toInstant(),
            rs.getString("resolution"), rs.getString("resolution_reason"), events, evidence);
    }

    private LockedIncident lock(String code) {
        List<LockedIncident> rows = jdbc.query("select i.id, i.status::text from incident i "
                + "left join deposit d on d.id = i.deposit_id "
                + "left join content_unit cu on cu.id = i.content_unit_id "
                + "left join lot l on l.id = cu.lot_id "
                + "where coalesce(d.center_id, l.center_id) = ? and i.code = ? for update of i",
            (rs, index) -> new LockedIncident(rs.getObject("id", UUID.class), rs.getString("status")),
            context.centerId(), normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Incident not found.");
        return rows.getFirst();
    }

    private void requireOpen(LockedIncident incident) {
        if (incident.status().equals("RESOLVED") || incident.status().equals("DISCARDED")) {
            throw new BusinessRuleException("Closed incident cannot be changed.");
        }
    }

    private void event(UUID id, String type, String note) {
        jdbc.update("insert into incident_event(id, incident_id, event_type, note, created_by_id) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), id, type, note,
            context.userId());
    }

    private UUID responsibleId(String value, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from app_user where center_id = ? and active = true "
                + "and (lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsable no encontrado en el centro actual: " + value);
        return ids.getFirst();
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private Object[] prependZoneFilter() {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(context.centerId());
        coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter filter = context.readZoneFilter("d");
        if (!filter.allZones() && !filter.zoneIds().isEmpty()) params.addAll(filter.zoneIds());
        return params.toArray();
    }

    private static final String INCIDENT_SELECT = "select i.id, i.code, i.title, d.code as deposit_code, "
        + "cu.code as content_code, i.priority::text, i.status::text, u.full_name as responsible, "
        + "u.username as responsible_username, "
        + "i.opened_at, i.silenced_until, i.resolution::text, i.resolution_reason "
        + "from incident i left join deposit d on d.id = i.deposit_id "
        + "left join content_unit cu on cu.id = i.content_unit_id "
        + "left join lot l on l.id = cu.lot_id "
        + "left join app_user u on u.id = i.responsible_id";

    private record LockedIncident(UUID id, String status) {}
}
