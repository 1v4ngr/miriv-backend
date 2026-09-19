package coop.miriv.enology.tracking.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter;
import coop.miriv.enology.tracking.dto.TrackingDto.ContentInfo;
import coop.miriv.enology.tracking.dto.TrackingDto.LatestContent;
import coop.miriv.enology.tracking.dto.TrackingDto.LatestReading;
import coop.miriv.enology.tracking.dto.TrackingDto.Event;
import coop.miriv.enology.tracking.dto.TrackingDto.OverviewCell;
import coop.miriv.enology.tracking.dto.TrackingDto.OverviewResponse;
import coop.miriv.enology.tracking.dto.TrackingDto.OverviewRow;
import coop.miriv.enology.tracking.dto.TrackingDto.ParameterInfo;
import coop.miriv.enology.tracking.dto.TrackingDto.Point;
import coop.miriv.enology.tracking.dto.TrackingDto.Reading;
import coop.miriv.enology.tracking.dto.TrackingDto.SeriesResponse;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRange;
import coop.miriv.enology.tracking.service.ParameterTargetService.Target;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for "Seguimiento": analytical series of one or many contents, the events that explain them
 * (transfers, operations, state reviews) and the cellar-wide status matrix. Everything is read-only,
 * scoped to the user's center and readable zones, and uses bulk queries (no per-sample lookups).
 */
@Service
@Transactional(readOnly = true)
public class TrackingService {

    static final int MAX_CONTENTS = 40;
    static final int MAX_PARAMETERS = 30;
    private static final int MAX_ANCESTOR_DEPTH = 8;
    private static final List<String> DEFAULT_OVERVIEW_PARAMETERS = List.of("DENSITY", "REDUCING_SUGARS", "ETHANOL",
        "TOTAL_ACIDITY", "VOLATILE_ACIDITY", "PH", "FREE_SO2", "L_MALIC_ACID");

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ParameterTargetService targets;

    public TrackingService(JdbcTemplate jdbc, CurrentUserContext context, ParameterTargetService targets) {
        this.jdbc = jdbc;
        this.context = context;
        this.targets = targets;
    }

    // ------------------------------------------------------------------ parameters

    /** Parameters that have at least one current result, or the whole catalog when {@code all} (for target setup). */
    public List<ParameterInfo> parameters(boolean all) {
        return jdbc.query("select p.code, p.name, p.reference_unit, p.decimal_places from parameter p "
                + (all ? "" : "where exists (select 1 from result r where r.parameter_id = p.id and r.is_current) ")
                + "order by p.name",
            (rs, n) -> new ParameterInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    // ------------------------------------------------------------------ series

    public SeriesResponse series(List<String> contentCodes, List<String> parameterCodes, Instant from, Instant to,
                                 boolean includeAncestors) {
        contentCodes = clean(contentCodes);
        parameterCodes = clean(parameterCodes);
        if (contentCodes.isEmpty()) throw new BusinessRuleException("Elige al menos un contenido.");
        if (parameterCodes.isEmpty()) throw new BusinessRuleException("Elige al menos un parámetro.");
        if (contentCodes.size() > MAX_CONTENTS) throw new BusinessRuleException("Máximo " + MAX_CONTENTS + " contenidos a la vez.");
        if (parameterCodes.size() > MAX_PARAMETERS) throw new BusinessRuleException("Máximo " + MAX_PARAMETERS + " parámetros a la vez.");

        Map<UUID, ContentRow> rows = new LinkedHashMap<>(loadContents("c.code", contentCodes));
        Map<UUID, String> ancestorOf = includeAncestors ? addAncestors(rows) : Map.of();
        List<ParameterInfo> parameters = loadParameters(parameterCodes);
        if (rows.isEmpty() || parameters.isEmpty()) {
            return new SeriesResponse(infos(rows, ancestorOf), parameters, List.of(), List.of());
        }

        List<Object> args = new ArrayList<>(rows.keySet());
        StringBuilder sql = new StringBuilder("""
            select c.code content_code, p.code param_code, s.taken_at, r.numeric_value, r.qualifier::text qualifier,
                   r.qualifier_limit, r.validated, s.code sample_code, pm.name method_name
              from result r
              join analysis a on a.id = r.analysis_id
              join sample s on s.id = a.sample_id
              join content_unit c on c.id = s.content_unit_id
              join parameter p on p.id = r.parameter_id
              left join parameter_method pm on pm.id = r.method_id
             where r.is_current and a.status <> 'INVALIDATED'::analysis_status
            """);
        sql.append(" and c.id in (").append(marks(rows.size())).append(")");
        sql.append(" and p.code in (").append(marks(parameters.size())).append(")");
        parameters.forEach(parameter -> args.add(parameter.code()));
        if (from != null) { sql.append(" and s.taken_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" and s.taken_at <= ?"); args.add(Timestamp.from(to)); }
        sql.append(" order by s.taken_at, r.created_at");
        List<Point> points = jdbc.query(sql.toString(), (rs, n) -> new Point(rs.getString("content_code"),
            rs.getString("param_code"), instant(rs, "taken_at"), rs.getBigDecimal("numeric_value"), rs.getString("qualifier"),
            rs.getBigDecimal("qualifier_limit"), rs.getBoolean("validated"), rs.getString("sample_code"),
            rs.getString("method_name")), args.toArray());

        List<Target> allTargets = targets.all();
        Map<UUID, String> phases = alcoholicPhases(rows.keySet());
        List<TargetRange> ranges = new ArrayList<>();
        for (Map.Entry<UUID, ContentRow> entry : rows.entrySet()) {
            for (ParameterInfo parameter : parameters) {
                Target target = ParameterTargetService.resolve(allTargets, parameter.code(), entry.getValue().categoryCode(),
                    phases.get(entry.getKey()), entry.getValue().code());
                if (target != null) {
                    ranges.add(new TargetRange(entry.getValue().code(), parameter.code(), target.warnMin(), target.warnMax(),
                        target.critMin(), target.critMax()));
                }
            }
        }
        return new SeriesResponse(infos(rows, ancestorOf), parameters, points, ranges);
    }

    // ------------------------------------------------------------------ events

    public List<Event> events(List<String> contentCodes, Instant from, Instant to) {
        contentCodes = clean(contentCodes);
        if (contentCodes.isEmpty()) return List.of();
        if (contentCodes.size() > MAX_CONTENTS * 2) throw new BusinessRuleException("Demasiados contenidos.");
        Map<UUID, ContentRow> rows = loadContents("c.code", contentCodes);
        if (rows.isEmpty()) return List.of();
        Set<UUID> ids = rows.keySet();
        List<Event> events = new ArrayList<>();
        events.addAll(movementEvents(ids, rows));
        events.addAll(operationEvents(ids, rows));
        events.addAll(reviewEvents(ids, rows));
        return events.stream()
            .filter(event -> (from == null || !event.at().isBefore(from)) && (to == null || !event.at().isAfter(to)))
            .sorted(Comparator.comparing(Event::at))
            .toList();
    }

    private List<Event> movementEvents(Set<UUID> ids, Map<UUID, ContentRow> rows) {
        List<Object> args = new ArrayList<>(ids);
        args.addAll(ids);
        String sql = """
            select m.effective_at, m.type::text type, m.code, m.reason, ml.volume_liters, ml.loss_liters,
                   ml.source_content_unit_id, ml.destination_content_unit_id, sd.code src_deposit, dd.code dst_deposit
              from movement m
              join movement_line ml on ml.movement_id = m.id
              left join deposit sd on sd.id = ml.source_deposit_id
              left join deposit dd on dd.id = ml.destination_deposit_id
             where m.status = 'EXECUTED'::movement_status
               and (ml.source_content_unit_id in (%s) or ml.destination_content_unit_id in (%s))
            """.formatted(marks(ids.size()), marks(ids.size()));
        List<Event> out = new ArrayList<>();
        jdbc.query(sql, (RowCallback) rs -> {
            String type = rs.getString("type");
            String src = rs.getString("src_deposit");
            String dst = rs.getString("dst_deposit");
            String label = switch (type) {
                case "ENTRY" -> "Entrada a " + or(dst, "depósito");
                case "TRANSFER_FULL", "TRANSFER_PARTIAL" -> "Trasiego " + or(src, "?") + " → " + or(dst, "?");
                case "MIX" -> "Mezcla en " + or(dst, "depósito");
                case "SPLIT" -> "Desdoble desde " + or(src, "depósito");
                case "EXIT" -> "Salida de " + or(src, "depósito");
                case "LOSS" -> "Merma en " + or(src, "depósito");
                default -> "Ajuste de volumen en " + or(dst != null ? dst : src, "depósito");
            };
            BigDecimal volume = rs.getBigDecimal("volume_liters");
            String detail = (volume == null ? "" : volume.stripTrailingZeros().toPlainString() + " L · ")
                + rs.getString("code") + (rs.getString("reason") == null ? "" : " · " + rs.getString("reason"));
            for (String column : List.of("source_content_unit_id", "destination_content_unit_id")) {
                UUID id = rs.getObject(column, UUID.class);
                ContentRow row = id == null ? null : rows.get(id);
                if (row != null) out.add(new Event(row.code(), instant(rs, "effective_at"), movementType(type), label, detail));
            }
        }, args.toArray());
        return out.stream().distinct().toList();
    }

    private static String movementType(String type) {
        return switch (type) {
            case "TRANSFER_FULL", "TRANSFER_PARTIAL" -> "TRANSFER";
            default -> type;
        };
    }

    private List<Event> operationEvents(Set<UUID> ids, Map<UUID, ContentRow> rows) {
        String sql = """
            select o.content_unit_id, o.executed_at, o.type::text type, o.code, o.follow_up_note,
                   string_agg(trim(concat(a.product_name, ' ', coalesce(a.actual_quantity::text, ''), ' ', coalesce(a.unit, ''))), '; ') additions
              from operation o left join operation_addition a on a.operation_id = o.id
             where o.executed_at is not null
               and o.status in ('EXECUTED'::operation_status, 'EXECUTED_WITH_DEVIATION'::operation_status)
               and o.content_unit_id in (%s)
             group by o.id
            """.formatted(marks(ids.size()));
        List<Event> out = new ArrayList<>();
        jdbc.query(sql, (RowCallback) rs -> {
            ContentRow row = rows.get(rs.getObject("content_unit_id", UUID.class));
            if (row == null) return;
            String additions = rs.getString("additions");
            String note = rs.getString("follow_up_note");
            out.add(new Event(row.code(), instant(rs, "executed_at"), "OPERATION", operationLabel(rs.getString("type")),
                (additions == null || additions.isBlank() ? "" : additions + " · ") + rs.getString("code")
                    + (note == null || note.isBlank() ? "" : " · " + note)));
        }, ids.toArray());
        return out;
    }

    private static String operationLabel(String type) {
        return switch (type) {
            case "INOCULATION" -> "Inoculación";
            case "NUTRITION" -> "Nutrición";
            case "SULFITING" -> "Sulfitado";
            case "CORRECTION" -> "Corrección";
            case "PUMP_OVER" -> "Remontado";
            case "AERATION" -> "Aireación";
            case "SETPOINT_CHANGE" -> "Cambio de consigna";
            case "FILTRATION" -> "Filtración";
            case "STABILIZATION" -> "Estabilización";
            case "CLEANING" -> "Limpieza";
            default -> type;
        };
    }

    private List<Event> reviewEvents(Set<UUID> ids, Map<UUID, ContentRow> rows) {
        String sql = "select r.content_unit_id, r.reviewed_at, r.process::text process, r.previous_status, r.decision, r.reason "
            + "from fermentation_state_review r where r.content_unit_id in (" + marks(ids.size()) + ")";
        List<Event> out = new ArrayList<>();
        jdbc.query(sql, (RowCallback) rs -> {
            ContentRow row = rows.get(rs.getObject("content_unit_id", UUID.class));
            if (row == null) return;
            String process = "ALCOHOLIC".equals(rs.getString("process")) ? "alcohólica" : "maloláctica";
            String previous = rs.getString("previous_status");
            out.add(new Event(row.code(), instant(rs, "reviewed_at"), "STATE_REVIEW",
                "Revisión " + process + ": " + rs.getString("decision"),
                (previous == null ? "" : previous + " → ") + rs.getString("decision")
                    + (rs.getString("reason") == null ? "" : " · " + rs.getString("reason"))));
        }, ids.toArray());
        return out;
    }

    // ------------------------------------------------------------------ overview

    public OverviewResponse overview(List<String> parameterCodes) {
        List<String> codes = clean(parameterCodes);
        if (codes.isEmpty()) codes = DEFAULT_OVERVIEW_PARAMETERS;
        if (codes.size() > MAX_PARAMETERS) throw new BusinessRuleException("Máximo " + MAX_PARAMETERS + " parámetros a la vez.");
        List<ParameterInfo> parameters = loadParameters(codes);

        ZoneFilter zones = context.readZoneFilter("d");
        List<Object> args = new ArrayList<>();
        args.add(context.centerId());
        args.addAll(zones.zoneIds());
        record Active(UUID id, String content, String deposit, String depositName, String zone, String lot,
                      String categoryCode, String category, BigDecimal volume) {}
        List<Active> actives = jdbc.query("""
            select c.id, c.code, d.code deposit_code, d.name deposit_name, z.name zone_name, lot.code lot_code,
                   cat.code cat_code, cat.name cat_name, o.volume_liters
              from content_unit c
              join occupation o on o.content_unit_id = c.id and o.end_at is null
              join deposit d on d.id = o.deposit_id
              join lot on lot.id = c.lot_id
              left join zone z on z.id = d.zone_id
              left join internal_category cat on cat.id = c.category_id
             where c.active and d.center_id = ?""" + zones.sql() + " order by d.code",
            (rs, n) -> new Active(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("deposit_code"),
                rs.getString("deposit_name"), rs.getString("zone_name"), rs.getString("lot_code"), rs.getString("cat_code"),
                rs.getString("cat_name"), rs.getBigDecimal("volume_liters")), args.toArray());
        if (actives.isEmpty()) return new OverviewResponse(parameters, List.of());

        Set<UUID> ids = new LinkedHashSet<>();
        actives.forEach(active -> ids.add(active.id()));
        Map<UUID, String> alcoholic = statePhases(ids, "ALCOHOLIC");
        Map<UUID, String> malolactic = statePhases(ids, "MALOLACTIC");
        Map<UUID, Integer> openSamples = new HashMap<>();
        Map<UUID, Instant> lastSample = new HashMap<>();
        jdbc.query("select s.content_unit_id, max(s.taken_at) last_taken, "
                + "count(*) filter (where a.status not in ('VALIDATED'::analysis_status, 'INVALIDATED'::analysis_status)) open_count "
                + "from sample s left join analysis a on a.sample_id = s.id where s.content_unit_id in (" + marks(ids.size())
                + ") group by s.content_unit_id", (RowCallback) rs -> {
            UUID id = rs.getObject("content_unit_id", UUID.class);
            openSamples.put(id, rs.getInt("open_count"));
            lastSample.put(id, instant(rs, "last_taken"));
        }, ids.toArray());
        Map<UUID, Integer> openTasks = new HashMap<>();
        Map<UUID, Instant> nextDue = new HashMap<>();
        jdbc.query("select content_unit_id, count(*) n, min(due_at) next_due from task where content_unit_id in ("
                + marks(ids.size()) + ") and status in ('PENDING'::task_status, 'IN_PROGRESS'::task_status) "
                + "group by content_unit_id", (RowCallback) rs -> {
            UUID id = rs.getObject("content_unit_id", UUID.class);
            openTasks.put(id, rs.getInt("n"));
            nextDue.put(id, instant(rs, "next_due"));
        }, ids.toArray());

        // Latest two readings per content and parameter, in one query.
        Map<String, List<Reading>> readings = new HashMap<>();
        for (RawReading raw : latestReadings(ids, parameters.stream().map(ParameterInfo::code).toList(), 2)) {
            readings.computeIfAbsent(raw.contentId() + "|" + raw.parameter(), k -> new ArrayList<>()).add(raw.reading());
        }

        List<Target> allTargets = targets.all();
        Instant now = Instant.now();
        List<OverviewRow> rows = new ArrayList<>();
        for (Active active : actives) {
            String phase = alcoholic.get(active.id());
            List<OverviewCell> cells = new ArrayList<>();
            int worst = 0;
            for (ParameterInfo parameter : parameters) {
                List<Reading> pair = readings.getOrDefault(active.id() + "|" + parameter.code(), List.of());
                if (pair.isEmpty()) { cells.add(new OverviewCell(parameter.code(), null, null, null)); continue; }
                Target range = ParameterTargetService.resolve(allTargets, parameter.code(), active.categoryCode(), phase, active.content());
                Reading latest = withStatus(pair.get(0), range, now);
                Reading previous = pair.size() > 1 ? withStatus(pair.get(1), range, now) : null;
                worst = Math.max(worst, rank(latest.status()));
                cells.add(new OverviewCell(parameter.code(), latest, previous, trend(latest, previous)));
            }
            Instant last = lastSample.get(active.id());
            rows.add(new OverviewRow(active.content(), active.deposit(), active.depositName(), active.zone(), active.lot(),
                active.category(), active.volume(), alcoholic.get(active.id()), malolactic.get(active.id()),
                openSamples.getOrDefault(active.id(), 0), last == null ? null : ChronoUnit.DAYS.between(last, now),
                openTasks.getOrDefault(active.id(), 0), nextDue.get(active.id()), statusName(worst), cells));
        }
        // Most worrying first: worst status, then longest without a sample (never sampled counts as longest), then tank.
        rows.sort(Comparator.<OverviewRow>comparingInt(row -> -rank(row.worstStatus()))
            .thenComparing(row -> row.daysSinceLastSample() == null ? Long.MIN_VALUE : -row.daysSinceLastSample())
            .thenComparing(OverviewRow::deposit));
        return new OverviewResponse(parameters, rows);
    }

    private static Reading withStatus(Reading reading, Target range, Instant now) {
        return new Reading(reading.value(), reading.qualifier(), reading.limit(), reading.takenAt(), reading.sampleCode(),
            ChronoUnit.DAYS.between(reading.takenAt(), now),
            ParameterTargetService.evaluate(reading.value(), reading.qualifier(), reading.limit(), range));
    }

    private static String trend(Reading latest, Reading previous) {
        if (previous == null || latest.value() == null || previous.value() == null
            || !"NONE".equals(latest.qualifier()) || !"NONE".equals(previous.qualifier())) return null;
        int cmp = latest.value().compareTo(previous.value());
        return cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "FLAT";
    }

    private static int rank(String status) {
        return switch (status == null ? "NONE" : status) {
            case "CRIT" -> 3;
            case "WARN" -> 2;
            case "OK" -> 1;
            default -> 0;
        };
    }

    private static String statusName(int rank) {
        return switch (rank) { case 3 -> "CRIT"; case 2 -> "WARN"; case 1 -> "OK"; default -> "NONE"; };
    }


    /** One current, non-invalidated result with the sample it came from (rn = 1 is the most recent). */
    private record RawReading(UUID contentId, String parameter, Reading reading, boolean validated) {}

    /**
     * Latest {@code perParameter} readings per content and parameter, most recent first, in a single query.
     * An empty {@code parameterCodes} means every parameter.
     */
    private List<RawReading> latestReadings(Set<UUID> contentIds, List<String> parameterCodes, int perParameter) {
        if (contentIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(contentIds);
        StringBuilder sql = new StringBuilder("""
            select * from (
              select s.content_unit_id, p.code param_code, s.taken_at, r.numeric_value, r.qualifier::text qualifier,
                     r.qualifier_limit, r.validated, s.code sample_code,
                     row_number() over (partition by s.content_unit_id, r.parameter_id
                                        order by s.taken_at desc, r.created_at desc) rn
                from result r
                join analysis a on a.id = r.analysis_id
                join sample s on s.id = a.sample_id
                join parameter p on p.id = r.parameter_id
               where r.is_current and a.status <> 'INVALIDATED'::analysis_status
            """);
        sql.append(" and s.content_unit_id in (").append(marks(contentIds.size())).append(")");
        if (!parameterCodes.isEmpty()) {
            sql.append(" and p.code in (").append(marks(parameterCodes.size())).append(")");
            args.addAll(parameterCodes);
        }
        sql.append(") t where rn <= ").append(perParameter).append(" order by rn");
        List<RawReading> out = new ArrayList<>();
        jdbc.query(sql.toString(), (RowCallback) rs -> out.add(new RawReading(rs.getObject("content_unit_id", UUID.class),
            rs.getString("param_code"), new Reading(rs.getBigDecimal("numeric_value"), rs.getString("qualifier"),
                rs.getBigDecimal("qualifier_limit"), instant(rs, "taken_at"), rs.getString("sample_code"), null, null),
            rs.getBoolean("validated"))), args.toArray());
        return out;
    }

    // ------------------------------------------------------------------ latest (KPI, blend simulator)

    /** Latest reading of every parameter of the given contents, plus volume and deposit capacity. */
    public List<LatestContent> latest(List<String> contentCodes) {
        contentCodes = clean(contentCodes);
        if (contentCodes.isEmpty()) throw new BusinessRuleException("Elige al menos un contenido.");
        if (contentCodes.size() > MAX_CONTENTS) throw new BusinessRuleException("Máximo " + MAX_CONTENTS + " contenidos a la vez.");
        Map<UUID, ContentRow> rows = loadContents("c.code", contentCodes);
        if (rows.isEmpty()) return List.of();
        Set<UUID> ids = rows.keySet();

        record Active(String deposit, BigDecimal capacity, BigDecimal volume) {}
        Map<UUID, Active> active = new HashMap<>();
        jdbc.query("select o.content_unit_id, d.code, d.useful_capacity_liters, o.volume_liters from occupation o "
                + "join deposit d on d.id = o.deposit_id where o.end_at is null and o.content_unit_id in ("
                + marks(ids.size()) + ")", (RowCallback) rs -> active.put(rs.getObject(1, UUID.class),
            new Active(rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))), ids.toArray());

        Map<String, ParameterInfo> catalog = new HashMap<>();
        jdbc.query("select code, name, reference_unit, decimal_places from parameter", (RowCallback) rs ->
            catalog.put(rs.getString(1), new ParameterInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))));

        Map<UUID, List<LatestReading>> byContent = new HashMap<>();
        Instant now = Instant.now();
        for (RawReading raw : latestReadings(ids, List.of(), 1)) {
            ParameterInfo parameter = catalog.get(raw.parameter());
            if (parameter == null) continue;
            Reading reading = raw.reading();
            byContent.computeIfAbsent(raw.contentId(), k -> new ArrayList<>()).add(new LatestReading(parameter.code(),
                parameter.name(), parameter.unit(), parameter.decimals(), reading.value(), reading.qualifier(), reading.limit(),
                reading.takenAt(), ChronoUnit.DAYS.between(reading.takenAt(), now), raw.validated(), reading.sampleCode()));
        }
        Map<UUID, String> phases = alcoholicPhases(ids);
        List<Target> allTargets = targets.all();
        List<LatestContent> out = new ArrayList<>();
        for (ContentRow row : rows.values()) {
            Active current = active.get(row.id());
            List<LatestReading> readings = new ArrayList<>(byContent.getOrDefault(row.id(), List.of()));
            readings.sort(Comparator.comparing(LatestReading::name));
            // Ranges that apply to this content, so a caller can colour readings without admin access to the targets.
            List<TargetRange> ranges = new ArrayList<>();
            for (LatestReading reading : readings) {
                Target target = ParameterTargetService.resolve(allTargets, reading.parameter(), row.categoryCode(), phases.get(row.id()), row.code());
                if (target != null) ranges.add(new TargetRange(row.code(), reading.parameter(), target.warnMin(), target.warnMax(), target.critMin(), target.critMax()));
            }
            out.add(new LatestContent(row.code(), current == null ? null : current.deposit(),
                current == null ? null : current.capacity(), row.lot(), row.categoryCode(), row.category(),
                current == null ? null : current.volume(), phases.get(row.id()), readings, ranges));
        }
        return out;
    }

    // ------------------------------------------------------------------ shared helpers

    private record ContentRow(UUID id, String code, String deposit, String lot, String categoryCode, String category,
                              Instant startedAt) {}

    /** Contents the user may read: they occupy (or occupied) a deposit of their center in a readable zone. */
    private Map<UUID, ContentRow> loadContents(String column, Collection<?> values) {
        if (values.isEmpty()) return Map.of();
        ZoneFilter zones = context.readZoneFilter("vd");
        List<Object> args = new ArrayList<>(values);
        args.add(context.centerId());
        args.addAll(zones.zoneIds());
        String sql = """
            select c.id, c.code, lot.code lot_code, cat.code cat_code, cat.name cat_name,
                   (select min(o.start_at) from occupation o where o.content_unit_id = c.id) started,
                   (select d.code from occupation o join deposit d on d.id = o.deposit_id where o.content_unit_id = c.id
                     order by (o.end_at is null) desc, o.start_at desc limit 1) deposit_code
              from content_unit c
              join lot on lot.id = c.lot_id
              left join internal_category cat on cat.id = c.category_id
             where %s in (%s)
               and exists (select 1 from occupation vo join deposit vd on vd.id = vo.deposit_id
                            where vo.content_unit_id = c.id and vd.center_id = ?%s)
            """.formatted(column, marks(values.size()), zones.sql());
        Map<UUID, ContentRow> out = new LinkedHashMap<>();
        Map<String, ContentRow> byCode = new HashMap<>();
        jdbc.query(sql, (RowCallback) rs -> {
            Timestamp started = rs.getTimestamp("started");
            ContentRow row = new ContentRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("deposit_code"),
                rs.getString("lot_code"), rs.getString("cat_code"), rs.getString("cat_name"),
                started == null ? null : started.toInstant());
            byCode.put(row.code(), row);
            out.put(row.id(), row);
        }, args.toArray());
        if ("c.code".equals(column)) {
            // Keep the order the caller asked for.
            Map<UUID, ContentRow> ordered = new LinkedHashMap<>();
            for (Object code : values) {
                ContentRow row = byCode.get(String.valueOf(code));
                if (row != null) ordered.put(row.id(), row);
            }
            return ordered;
        }
        return out;
    }

    /** Adds the parent contents (through content_unit_lineage) of the requested ones, up to a fixed depth. */
    private Map<UUID, String> addAncestors(Map<UUID, ContentRow> rows) {
        Map<UUID, String> ancestorOf = new HashMap<>();
        Map<UUID, String> descendant = new HashMap<>();
        rows.values().forEach(row -> descendant.put(row.id(), row.code()));
        Set<UUID> frontier = new LinkedHashSet<>(rows.keySet());
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH && !frontier.isEmpty(); depth++) {
            Map<UUID, UUID> parentToChild = new LinkedHashMap<>();
            jdbc.query("select content_unit_id, parent_content_unit_id from content_unit_lineage where content_unit_id in ("
                + marks(frontier.size()) + ")", (RowCallback) rs -> {
                UUID parent = rs.getObject("parent_content_unit_id", UUID.class);
                if (!rows.containsKey(parent)) parentToChild.putIfAbsent(parent, rs.getObject("content_unit_id", UUID.class));
            }, frontier.toArray());
            if (parentToChild.isEmpty()) break;
            Map<UUID, ContentRow> loaded = loadContents("c.id", parentToChild.keySet());
            frontier = new LinkedHashSet<>();
            for (ContentRow parent : loaded.values()) {
                rows.put(parent.id(), parent);
                String root = descendant.get(parentToChild.get(parent.id()));
                ancestorOf.put(parent.id(), root);
                descendant.put(parent.id(), root);
                frontier.add(parent.id());
            }
        }
        return ancestorOf;
    }

    /** ancestorOf maps an ancestor to the requested content it descends into. */
    private static List<ContentInfo> infos(Map<UUID, ContentRow> rows, Map<UUID, String> ancestorOf) {
        return rows.values().stream().map(row -> new ContentInfo(row.code(), row.deposit(), row.lot(), row.category(),
            row.startedAt(), ancestorOf.containsKey(row.id()), ancestorOf.get(row.id()))).toList();
    }

    private List<ParameterInfo> loadParameters(List<String> codes) {
        Map<String, ParameterInfo> byCode = new HashMap<>();
        jdbc.query("select code, name, reference_unit, decimal_places from parameter where code in (" + marks(codes.size()) + ")",
            (RowCallback) rs -> byCode.put(rs.getString(1), new ParameterInfo(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getInt(4))), codes.toArray());
        return codes.stream().map(byCode::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Alcoholic fermentation state (confirmed, else estimated) per content: the "phase" targets can be tied to. */
    private Map<UUID, String> alcoholicPhases(Collection<UUID> ids) {
        return statePhases(ids, "ALCOHOLIC");
    }

    private Map<UUID, String> statePhases(Collection<UUID> ids, String process) {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<Object> args = new ArrayList<>(ids);
        args.add(process);
        jdbc.query("select content_unit_id, coalesce(confirmed_status, estimated_status) status from fermentation_state "
                + "where content_unit_id in (" + marks(ids.size()) + ") and process = ?::fermentation_process",
            (RowCallback) rs -> out.put(rs.getObject(1, UUID.class), rs.getString(2)), args.toArray());
        return out;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** RowCallbackHandler that lets lambdas throw SQLException. */
    @FunctionalInterface
    private interface RowCallback extends org.springframework.jdbc.core.RowCallbackHandler {
        @Override
        void processRow(ResultSet rs) throws SQLException;
    }
}
