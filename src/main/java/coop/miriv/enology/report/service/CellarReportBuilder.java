package coop.miriv.enology.report.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter;
import coop.miriv.enology.report.dto.ReportDto.Filters;
import coop.miriv.enology.report.model.CellarReport;
import coop.miriv.enology.report.model.CellarReport.Alert;
import coop.miriv.enology.report.model.CellarReport.AnalyticRow;
import coop.miriv.enology.report.model.CellarReport.Block;
import coop.miriv.enology.report.model.CellarReport.Cell;
import coop.miriv.enology.report.model.CellarReport.DepositReport;
import coop.miriv.enology.report.model.CellarReport.EmptyDeposit;
import coop.miriv.enology.report.model.CellarReport.Event;
import coop.miriv.enology.report.model.CellarReport.Latest;
import coop.miriv.enology.report.model.CellarReport.Meta;
import coop.miriv.enology.report.model.CellarReport.Parameter;
import coop.miriv.enology.report.model.CellarReport.PhaseCount;
import coop.miriv.enology.report.model.CellarReport.PhaseRef;
import coop.miriv.enology.report.model.CellarReport.Point;
import coop.miriv.enology.report.model.CellarReport.Range;
import coop.miriv.enology.report.model.CellarReport.Segment;
import coop.miriv.enology.report.model.CellarReport.Series;
import coop.miriv.enology.report.model.CellarReport.TableRow;
import coop.miriv.enology.report.service.ReportPhaseService.Phase;
import coop.miriv.enology.tracking.dto.TrackingDto.AlertView;
import coop.miriv.enology.tracking.service.AlertService;
import coop.miriv.enology.tracking.service.ParameterTargetService;
import coop.miriv.enology.tracking.service.ParameterTargetService.Target;
import coop.miriv.enology.tracking.service.TrackingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the data of a cellar-status report: the deposits in scope with their current content, the phase
 * each content is in now and the phases it went through (rebuilt from the dated state reviews and the
 * must → wine reclassification), every current result of the period with the phase and target that applied
 * on its sampling date, the events that explain the curves and the alerts that hold now.
 *
 * <p>Everything is read in bulk (one query per kind of data, not per deposit) and scoped to the user's
 * center and readable zones.
 */
@Service
public class CellarReportBuilder {

    /** Phase used for a content no configured phase matches. */
    static final PhaseRef NO_PHASE = new PhaseRef(null, "Sin fase asignada", "#8a7f86",
        "Ninguna fase configurada encaja con su categoría y estados.",
        "Ninguna fase configurada encaja; se grafican los parámetros con datos.", List.of());
    private static final int MAX_NO_PHASE_PARAMETERS = 8;
    private static final int EVENT_CHUNK = 80;

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ReportPhaseService phaseService;
    private final ParameterTargetService targetService;
    private final TrackingService tracking;
    private final AlertService alertService;
    private final ZoneId zone;

    public CellarReportBuilder(JdbcTemplate jdbc, CurrentUserContext context, ReportPhaseService phaseService,
                               ParameterTargetService targetService, TrackingService tracking, AlertService alertService,
                               @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.phaseService = phaseService;
        this.targetService = targetService;
        this.tracking = tracking;
        this.alertService = alertService;
        this.zone = ZoneId.of(timezone);
    }

    // ------------------------------------------------------------------ internal rows

    private record DepositRow(String code, String name, String zoneCode, String zoneName, BigDecimal capacity,
                              String status, UUID contentId, String content, String lot, String categoryCode,
                              String categoryName, BigDecimal volume, Instant enteredDeposit, Instant contentStart,
                              String plan) {}

    private record Change(Instant at, String kind, String value) {}

    /** State of a content at a moment: what the phase rule looks at. */
    private record State(String categoryCode, String alcoholic, String malolactic) {}

    /** A raw phase segment over the whole life of a content (before clipping to the report period). */
    private record RawSegment(Phase phase, Instant from, Instant to, State state) {}

    private record RawResult(UUID contentId, String sampleCode, Instant takenAt, String depositAtSampling,
                             String parameter, BigDecimal value, String qualifier, BigDecimal limit, boolean validated,
                             Instant validatedAt, String validatedBy, String method, String laboratory) {}

    // ------------------------------------------------------------------ build

    @Transactional(readOnly = true)
    public CellarReport build(String code, String title, Filters filters, boolean includeProvisional) {
        Filters scope = filters == null ? Filters.empty() : filters;
        String periodMode = scope.periodOrDefault();
        if (!Set.of(Filters.DEPOSIT_ENTRY, Filters.CONTENT_START, Filters.RANGE).contains(periodMode)) {
            throw new BusinessRuleException("Periodo no válido: " + periodMode);
        }
        Instant now = Instant.now();
        Instant rangeFrom = Filters.RANGE.equals(periodMode) && scope.from() != null
            ? scope.from().atStartOfDay(zone).toInstant() : null;
        Instant rangeTo = Filters.RANGE.equals(periodMode) && scope.to() != null
            ? scope.to().plusDays(1).atStartOfDay(zone).toInstant() : null;
        if (rangeFrom != null && rangeTo != null && !rangeFrom.isBefore(rangeTo)) {
            throw new BusinessRuleException("La fecha inicial debe ser anterior o igual a la final.");
        }
        if (rangeTo != null && rangeTo.isAfter(now)) rangeTo = now;

        List<Phase> phases = phaseService.all();
        Map<String, String> categoryNames = new HashMap<>();
        Map<String, String> categoryCodesByName = new HashMap<>();
        jdbc.query("select code, name from internal_category", (RowCallbackHandler) rs -> {
            categoryNames.put(rs.getString(1), rs.getString(2));
            categoryCodesByName.put(rs.getString(2).toLowerCase(Locale.ROOT), rs.getString(1));
        });
        Map<String, Parameter> catalog = new LinkedHashMap<>();
        jdbc.query("select code, name, reference_unit, decimal_places from parameter order by name", (RowCallbackHandler) rs ->
            catalog.put(rs.getString(1), new Parameter(rs.getString(1), rs.getString(2), blankToNull(rs.getString(3)), rs.getInt(4))));

        // 1) Deposits in scope with their active content.
        List<DepositRow> all = deposits();
        Set<String> zoneFilter = upper(scope.zonesOrEmpty());
        Set<String> depositFilter = upper(scope.depositsOrEmpty());
        Set<String> categoryFilter = upper(scope.categoriesOrEmpty());
        Set<String> phaseFilter = upper(scope.phasesOrEmpty());
        List<DepositRow> inScope = all.stream()
            .filter(row -> zoneFilter.isEmpty() || (row.zoneCode() != null && zoneFilter.contains(row.zoneCode().toUpperCase(Locale.ROOT))))
            .filter(row -> depositFilter.isEmpty() || depositFilter.contains(row.code().toUpperCase(Locale.ROOT)))
            .toList();

        List<DepositRow> occupied = inScope.stream().filter(row -> row.contentId() != null)
            .filter(row -> categoryFilter.isEmpty() || (row.categoryCode() != null && categoryFilter.contains(row.categoryCode().toUpperCase(Locale.ROOT))))
            .toList();
        Set<UUID> ids = occupied.stream().map(DepositRow::contentId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, String> alcoholic = states(ids, "ALCOHOLIC");
        Map<UUID, String> malolactic = states(ids, "MALOLACTIC");

        // Current phase, then the phase filter.
        Map<UUID, Phase> currentPhase = new HashMap<>();
        for (DepositRow row : occupied) {
            currentPhase.put(row.contentId(), ReportPhaseService.resolve(phases, row.categoryCode(),
                alcoholic.get(row.contentId()), malolactic.get(row.contentId())));
        }
        if (!phaseFilter.isEmpty()) {
            occupied = occupied.stream().filter(row -> {
                Phase phase = currentPhase.get(row.contentId());
                return phaseFilter.contains(phase == null ? "NONE" : phase.code().toUpperCase(Locale.ROOT));
            }).toList();
            ids = occupied.stream().map(DepositRow::contentId).collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 2) History of every content in one go.
        Map<UUID, List<Change>> changes = changes(ids, categoryCodesByName);
        Map<UUID, List<RawResult>> results = results(ids, includeProvisional);
        Map<String, List<coop.miriv.enology.tracking.dto.TrackingDto.Event>> events = events(occupied);
        Map<String, List<AlertView>> alerts = alertService.alerts().stream()
            .collect(Collectors.groupingBy(AlertView::content, LinkedHashMap::new, Collectors.toList()));
        List<Target> targets = targetService.all();

        // 3) Assemble each deposit.
        List<DepositReport> deposits = new ArrayList<>();
        List<AnalyticRow> allRows = new ArrayList<>();
        for (DepositRow row : occupied) {
            UUID id = row.contentId();
            State current = new State(row.categoryCode(), alcoholic.get(id), malolactic.get(id));
            Instant lifeStart = firstOf(row.contentStart(), row.enteredDeposit(),
                results.getOrDefault(id, List.of()).stream().map(RawResult::takenAt).findFirst().orElse(null), now);
            List<RawSegment> life = timeline(phases, current, changes.getOrDefault(id, List.of()), lifeStart, now);

            Instant from = switch (periodMode) {
                case Filters.CONTENT_START -> lifeStart;
                case Filters.RANGE -> rangeFrom == null ? lifeStart : rangeFrom;
                default -> row.enteredDeposit() == null ? lifeStart : row.enteredDeposit();
            };
            Instant to = rangeTo == null ? now : rangeTo;

            List<AnalyticRow> rows = new ArrayList<>();
            for (RawResult raw : results.getOrDefault(id, List.of())) {
                if (raw.takenAt().isBefore(from) || raw.takenAt().isAfter(to)) continue;
                Parameter parameter = catalog.get(raw.parameter());
                if (parameter == null) continue;
                RawSegment at = segmentAt(life, raw.takenAt());
                Range range = range(ParameterTargetService.resolve(targets, parameter.code(), at.state().categoryCode(),
                    stateCode(at.state().alcoholic()), row.content()));
                String status = ParameterTargetService.evaluate(raw.value(), raw.qualifier(), raw.limit(), target(range));
                rows.add(new AnalyticRow(row.code(), row.content(), row.lot(), raw.depositAtSampling(), raw.sampleCode(),
                    raw.takenAt(), parameter, raw.value(), raw.qualifier(), raw.limit(), raw.method(), raw.laboratory(),
                    raw.validated(), raw.validatedAt(), raw.validatedBy(), categoryNames.get(at.state().categoryCode()),
                    ref(at.phase(), categoryNames), at.state().alcoholic(), at.state().malolactic(), range, status));
            }
            allRows.addAll(rows);

            List<Segment> segments = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            for (RawSegment raw : life) {
                Instant segFrom = raw.from().isBefore(from) ? from : raw.from();
                Instant segTo = raw.to().isAfter(to) ? to : raw.to();
                if (segFrom.isAfter(segTo)) continue;
                Segment segment = new Segment(ref(raw.phase(), categoryNames), segFrom, segTo, categoryNames.get(raw.state().categoryCode()),
                    raw.state().alcoholic(), raw.state().malolactic());
                segments.add(segment);
                List<AnalyticRow> inSegment = rows.stream()
                    .filter(item -> !item.takenAt().isBefore(segFrom) && !item.takenAt().isAfter(segTo)).toList();
                blocks.add(block(segment, raw, inSegment, catalog, targets, row.content()));
            }

            Phase phase = currentPhase.get(id);
            Instant phaseSince = life.isEmpty() ? null : life.getLast().from();
            List<Latest> latest = latest(rows, now);
            String worst = latest.stream().map(item -> item.row().status()).max(Comparator.comparingInt(CellarReportBuilder::rank)).orElse("NONE");
            Instant lastSample = rows.stream().map(AnalyticRow::takenAt).max(Comparator.naturalOrder()).orElse(null);
            int samples = (int) rows.stream().map(AnalyticRow::sampleCode).distinct().count();
            List<Event> contentEvents = events.getOrDefault(row.content(), List.of()).stream()
                .filter(event -> !event.at().isBefore(from) && !event.at().isAfter(to))
                .map(event -> new Event(event.at(), event.type(), event.label(), event.detail())).toList();
            List<Alert> contentAlerts = alerts.getOrDefault(row.content(), List.of()).stream()
                .map(alert -> new Alert(alert.rule(), alert.severity(), alert.since(), alert.detail())).toList();
            Integer fill = row.capacity() == null || row.capacity().signum() == 0 || row.volume() == null ? null
                : row.volume().multiply(BigDecimal.valueOf(100)).divide(row.capacity(), 0, RoundingMode.HALF_UP).intValue();

            deposits.add(new DepositReport(row.code(), row.name(), row.zoneName(), row.capacity(), row.content(), row.lot(),
                row.categoryCode(), row.categoryName(), row.volume(), fill, alcoholic.get(id), malolactic.get(id), row.plan(),
                row.enteredDeposit(), lifeStart, from, to, ref(phase, categoryNames), phaseSince, segments, blocks, latest, worst, lastSample,
                samples, contentEvents, contentAlerts));
        }

        // Phase order first (as configured), then deposit code.
        Map<String, Integer> order = new HashMap<>();
        phases.forEach(phase -> order.put(phase.code(), phase.position()));
        deposits.sort(Comparator.comparingInt((DepositReport d) -> d.phase().code() == null ? Integer.MAX_VALUE : order.getOrDefault(d.phase().code(), Integer.MAX_VALUE - 1))
            .thenComparing(DepositReport::deposit, CellarReportBuilder::naturalCompare));

        List<EmptyDeposit> empty = phaseFilter.isEmpty() && categoryFilter.isEmpty()
            ? inScope.stream().filter(row -> row.contentId() == null)
                .map(row -> new EmptyDeposit(row.code(), row.name(), row.zoneName(), row.capacity(), row.status())).toList()
            : List.of();

        allRows.sort(Comparator.comparing(AnalyticRow::deposit, CellarReportBuilder::naturalCompare)
            .thenComparing(AnalyticRow::takenAt).thenComparing(row -> row.parameter().name()));

        List<PhaseRef> refs = new ArrayList<>(phases.stream().filter(Phase::active).map(phase -> ref(phase, categoryNames)).toList());
        List<PhaseCount> counts = counts(refs, deposits);
        if (deposits.stream().anyMatch(d -> d.phase().code() == null)) refs.add(NO_PHASE);

        Meta meta = new Meta(code, title, context.center().getName(), context.user().getFullName(), now, periodMode,
            rangeFrom, rangeTo, includeProvisional, describe(scope, periodMode, phases), zone.getId());
        return new CellarReport(meta, counts, deposits, empty, allRows, refs);
    }

    // ------------------------------------------------------------------ form options

    /** Deposits the user can read, with what they hold now and the phase it is in (for the report form). */
    @Transactional(readOnly = true)
    public List<coop.miriv.enology.report.dto.ReportDto.DepositOption> depositOptions() {
        List<Phase> phases = phaseService.all();
        List<DepositRow> rows = deposits();
        Set<UUID> ids = rows.stream().map(DepositRow::contentId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> alcoholic = states(ids, "ALCOHOLIC");
        Map<UUID, String> malolactic = states(ids, "MALOLACTIC");
        return rows.stream().sorted(Comparator.comparing(DepositRow::code, CellarReportBuilder::naturalCompare)).map(row -> {
            String phase = null;
            if (row.contentId() != null) {
                Phase match = ReportPhaseService.resolve(phases, row.categoryCode(), alcoholic.get(row.contentId()), malolactic.get(row.contentId()));
                phase = match == null ? "NONE" : match.code();
            }
            return new coop.miriv.enology.report.dto.ReportDto.DepositOption(row.code(), row.name(), row.zoneCode(), row.content(),
                row.categoryCode(), phase);
        }).toList();
    }

    // ------------------------------------------------------------------ queries

    private List<DepositRow> deposits() {
        ZoneFilter zones = context.readZoneFilter("d");
        List<Object> args = new ArrayList<>();
        args.add(context.centerId());
        args.addAll(zones.zoneIds());
        return jdbc.query("""
            select d.code, d.name, z.code zone_code, z.name zone_name, d.useful_capacity_liters, d.status::text status,
                   c.id content_id, c.code content_code, lot.code lot_code, cat.code cat_code, cat.name cat_name,
                   o.volume_liters, o.start_at,
                   (select min(o2.start_at) from occupation o2 where o2.content_unit_id = c.id) content_start,
                   (select p.name from elaboration_plan p where p.content_unit_id = c.id limit 1) plan_name
              from deposit d
              left join zone z on z.id = d.zone_id
              left join occupation o on o.deposit_id = d.id and o.end_at is null
              left join content_unit c on c.id = o.content_unit_id
              left join lot on lot.id = c.lot_id
              left join internal_category cat on cat.id = c.category_id
             where d.active and d.center_id = ?""" + zones.sql() + " order by d.code",
            (rs, n) -> new DepositRow(rs.getString("code"), rs.getString("name"), rs.getString("zone_code"),
                rs.getString("zone_name"), rs.getBigDecimal("useful_capacity_liters"), rs.getString("status"),
                rs.getObject("content_id", UUID.class), rs.getString("content_code"), rs.getString("lot_code"),
                rs.getString("cat_code"), rs.getString("cat_name"), rs.getBigDecimal("volume_liters"),
                instant(rs, "start_at"), instant(rs, "content_start"), rs.getString("plan_name")),
            args.toArray());
    }

    /** Current state per content (confirmed, else estimated), stored as codes. */
    private Map<UUID, String> states(Collection<UUID> ids, String process) {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<Object> args = new ArrayList<>(ids);
        args.add(process);
        jdbc.query("select content_unit_id, coalesce(confirmed_status, estimated_status) from fermentation_state "
                + "where content_unit_id in (" + marks(ids.size()) + ") and process = ?::fermentation_process",
            (RowCallbackHandler) rs -> {
                String value = ReportPhaseService.stateOrNone(rs.getString(2));
                if (!ReportPhaseService.NO_STATE.equals(value)) out.put(rs.getObject(1, UUID.class), value);
            }, args.toArray());
        return out;
    }

    /** Dated changes of state and category per content, oldest first. */
    private Map<UUID, List<Change>> changes(Set<UUID> ids, Map<String, String> categoryCodesByName) {
        Map<UUID, List<Change>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("select content_unit_id, process::text, previous_status, decision, reviewed_at from fermentation_state_review "
                + "where content_unit_id in (" + marks(ids.size()) + ") order by reviewed_at", (RowCallbackHandler) rs -> {
            UUID id = rs.getObject(1, UUID.class);
            String kind = "ALCOHOLIC".equals(rs.getString(2)) ? "FA" : "FML";
            List<Change> list = out.computeIfAbsent(id, k -> new ArrayList<>());
            if (list.stream().noneMatch(change -> change.kind().equals(kind))) {
                // The first review tells what the state was before it.
                list.add(new Change(null, kind + "_INITIAL", rs.getString(3)));
            }
            list.add(new Change(instant(rs, "reviewed_at"), kind, rs.getString(4)));
        }, ids.toArray());
        jdbc.query("select entity_id, previous_value ->> 'category', new_value ->> 'category', created_at from audit_log "
                + "where entity_name = 'content_unit' and action = 'CONTENT_RECLASSIFIED' and entity_id in ("
                + marks(ids.size()) + ") order by created_at", (RowCallbackHandler) rs -> {
            UUID id = rs.getObject(1, UUID.class);
            List<Change> list = out.computeIfAbsent(id, k -> new ArrayList<>());
            if (list.stream().noneMatch(change -> change.kind().equals("CAT"))) {
                list.add(new Change(null, "CAT_INITIAL", categoryCode(rs.getString(2), categoryCodesByName)));
            }
            list.add(new Change(instant(rs, "created_at"), "CAT", categoryCode(rs.getString(3), categoryCodesByName)));
        }, ids.toArray());
        return out;
    }

    private Map<UUID, List<RawResult>> results(Set<UUID> ids, boolean includeProvisional) {
        Map<UUID, List<RawResult>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String sql = """
            select s.content_unit_id, s.code sample_code, s.taken_at, dd.code deposit_at_sampling, p.code param_code,
                   r.numeric_value, r.qualifier::text qualifier, r.qualifier_limit, r.validated, r.validated_at,
                   vu.full_name validated_by, pm.name method_name, a.laboratory_name
              from result r
              join analysis a on a.id = r.analysis_id
              join sample s on s.id = a.sample_id
              join parameter p on p.id = r.parameter_id
              left join parameter_method pm on pm.id = r.method_id
              left join deposit dd on dd.id = s.deposit_id_at_sampling
              left join app_user vu on vu.id = r.validated_by_id
             where r.is_current and a.status <> 'INVALIDATED'::analysis_status
               and s.content_unit_id in (%s)%s
             order by s.taken_at, s.code, p.name
            """.formatted(marks(ids.size()), includeProvisional ? "" : " and r.validated");
        jdbc.query(sql, (RowCallbackHandler) rs -> out.computeIfAbsent(rs.getObject("content_unit_id", UUID.class), k -> new ArrayList<>())
            .add(new RawResult(rs.getObject("content_unit_id", UUID.class), rs.getString("sample_code"), instant(rs, "taken_at"),
                rs.getString("deposit_at_sampling"), rs.getString("param_code"), rs.getBigDecimal("numeric_value"),
                rs.getString("qualifier"), rs.getBigDecimal("qualifier_limit"), rs.getBoolean("validated"),
                instant(rs, "validated_at"), rs.getString("validated_by"), rs.getString("method_name"),
                rs.getString("laboratory_name"))), ids.toArray());
        return out;
    }

    private Map<String, List<coop.miriv.enology.tracking.dto.TrackingDto.Event>> events(List<DepositRow> rows) {
        List<String> codes = rows.stream().map(DepositRow::content).toList();
        Map<String, List<coop.miriv.enology.tracking.dto.TrackingDto.Event>> out = new HashMap<>();
        for (int start = 0; start < codes.size(); start += EVENT_CHUNK) {
            List<String> chunk = codes.subList(start, Math.min(codes.size(), start + EVENT_CHUNK));
            tracking.events(chunk, null, null).forEach(event -> out.computeIfAbsent(event.content(), k -> new ArrayList<>()).add(event));
        }
        return out;
    }

    // ------------------------------------------------------------------ phase timeline

    /**
     * Phases a content went through. The state before the first dated change comes from that change's
     * "previous" value; the last stretch always uses the current state (it may carry an estimate the reviews
     * do not). Consecutive stretches in the same phase are merged.
     */
    private static List<RawSegment> timeline(List<Phase> phases, State current, List<Change> changes, Instant start,
                                             Instant now) {
        String category = current.categoryCode();
        String fa = current.alcoholic();
        String fml = current.malolactic();
        for (Change change : changes) {
            if (change.at() != null) continue;
            switch (change.kind()) {
                case "CAT_INITIAL" -> category = change.value();
                case "FA_INITIAL" -> fa = stateCode(change.value());
                case "FML_INITIAL" -> fml = stateCode(change.value());
                default -> { }
            }
        }
        List<Change> dated = changes.stream().filter(change -> change.at() != null)
            .sorted(Comparator.comparing(Change::at)).toList();

        List<RawSegment> out = new ArrayList<>();
        State state = new State(category, fa, fml);
        Instant from = start;
        for (Change change : dated) {
            Instant at = change.at().isBefore(start) ? start : change.at();
            State next = switch (change.kind()) {
                case "CAT" -> new State(change.value(), state.alcoholic(), state.malolactic());
                case "FA" -> new State(state.categoryCode(), stateCode(change.value()), state.malolactic());
                default -> new State(state.categoryCode(), state.alcoholic(), stateCode(change.value()));
            };
            if (at.isAfter(from)) out.add(new RawSegment(ReportPhaseService.resolve(phases, state.categoryCode(),
                state.alcoholic(), state.malolactic()), from, at, state));
            from = at;
            state = next;
        }
        out.add(new RawSegment(ReportPhaseService.resolve(phases, current.categoryCode(), current.alcoholic(),
            current.malolactic()), from, now.isAfter(from) ? now : from, current));

        // Merge neighbours in the same phase (a review that keeps the phase is not a new stretch).
        List<RawSegment> merged = new ArrayList<>();
        for (RawSegment segment : out) {
            if (!merged.isEmpty() && samePhase(merged.getLast().phase(), segment.phase())) {
                RawSegment previous = merged.removeLast();
                merged.add(new RawSegment(previous.phase(), previous.from(), segment.to(), segment.state()));
            } else {
                merged.add(segment);
            }
        }
        return merged;
    }

    private static RawSegment segmentAt(List<RawSegment> life, Instant at) {
        for (RawSegment segment : life) {
            if (!at.isAfter(segment.to())) return segment;
        }
        return life.getLast();
    }

    private static boolean samePhase(Phase a, Phase b) {
        return a == null ? b == null : b != null && a.id().equals(b.id());
    }

    // ------------------------------------------------------------------ blocks, latest, counts

    private static Block block(Segment segment, RawSegment raw, List<AnalyticRow> rows, Map<String, Parameter> catalog,
                               List<Target> targets, String content) {
        List<String> codes = raw.phase() != null ? raw.phase().parameters()
            : rows.stream().map(row -> row.parameter().code()).distinct().limit(MAX_NO_PHASE_PARAMETERS).toList();
        Map<String, List<AnalyticRow>> byParameter = rows.stream()
            .collect(Collectors.groupingBy(row -> row.parameter().code(), LinkedHashMap::new, Collectors.toList()));
        List<Series> series = new ArrayList<>();
        List<Parameter> columns = new ArrayList<>();
        for (String code : codes) {
            Parameter parameter = catalog.get(code);
            List<AnalyticRow> values = byParameter.getOrDefault(code, List.of());
            if (parameter == null || values.isEmpty()) continue;
            columns.add(parameter);
            List<Point> points = values.stream().filter(row -> row.value() != null && !"NOT_MEASURED".equals(row.qualifier()))
                .map(row -> new Point(row.takenAt(), row.value(), row.qualifier(), row.limit(), row.validated(), row.sampleCode(), row.status()))
                .toList();
            if (points.isEmpty()) continue;
            Range range = range(ParameterTargetService.resolve(targets, code, raw.state().categoryCode(),
                stateCode(raw.state().alcoholic()), content));
            series.add(new Series(parameter, points, range));
        }
        // One table row per sample, in time order.
        Map<String, List<AnalyticRow>> bySample = rows.stream()
            .collect(Collectors.groupingBy(AnalyticRow::sampleCode, LinkedHashMap::new, Collectors.toList()));
        List<TableRow> table = new ArrayList<>();
        for (List<AnalyticRow> sample : bySample.values()) {
            Map<String, AnalyticRow> values = new HashMap<>();
            sample.forEach(row -> values.put(row.parameter().code(), row));
            if (columns.stream().noneMatch(column -> values.containsKey(column.code()))) continue;
            List<Cell> cells = columns.stream().map(column -> {
                AnalyticRow row = values.get(column.code());
                return row == null ? null : new Cell(row.value(), row.qualifier(), row.limit(), row.status(), row.validated());
            }).toList();
            boolean provisional = sample.stream().anyMatch(row -> !row.validated());
            table.add(new TableRow(sample.getFirst().takenAt(), sample.getFirst().sampleCode(), provisional, cells));
        }
        table.sort(Comparator.comparing(TableRow::takenAt));
        return new Block(segment, series, columns, table);
    }

    private static List<Latest> latest(List<AnalyticRow> rows, Instant now) {
        Map<String, AnalyticRow> last = new LinkedHashMap<>();
        for (AnalyticRow row : rows) {
            if ("NOT_MEASURED".equals(row.qualifier())) continue;
            AnalyticRow seen = last.get(row.parameter().code());
            if (seen == null || !row.takenAt().isBefore(seen.takenAt())) last.put(row.parameter().code(), row);
        }
        return last.values().stream()
            .sorted(Comparator.comparing(row -> row.parameter().name(), String.CASE_INSENSITIVE_ORDER))
            .map(row -> new Latest(row.parameter(), row, ChronoUnit.DAYS.between(row.takenAt(), now)))
            .toList();
    }

    private static List<PhaseCount> counts(List<PhaseRef> phases, List<DepositReport> deposits) {
        List<PhaseRef> all = new ArrayList<>(phases);
        if (deposits.stream().anyMatch(d -> d.phase().code() == null)) all.add(NO_PHASE);
        List<PhaseCount> out = new ArrayList<>();
        for (PhaseRef phase : all) {
            List<DepositReport> in = deposits.stream()
                .filter(d -> phase.code() == null ? d.phase().code() == null : phase.code().equals(d.phase().code())).toList();
            BigDecimal volume = in.stream().map(DepositReport::volumeLiters).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new PhaseCount(phase, in.size(), volume, (int) in.stream().filter(d -> "WARN".equals(d.worstStatus())).count(),
                (int) in.stream().filter(d -> "CRIT".equals(d.worstStatus())).count()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private String describe(Filters filters, String periodMode, List<Phase> phases) {
        List<String> parts = new ArrayList<>();
        parts.add("Zonas: " + (filters.zonesOrEmpty().isEmpty() ? "todas" : String.join(", ", filters.zonesOrEmpty())));
        parts.add("Depósitos: " + (filters.depositsOrEmpty().isEmpty() ? "todos" : String.join(", ", filters.depositsOrEmpty())));
        Map<String, String> names = new HashMap<>();
        phases.forEach(phase -> names.put(phase.code().toUpperCase(Locale.ROOT), phase.name()));
        names.put("NONE", NO_PHASE.name());
        parts.add("Fases: " + (filters.phasesOrEmpty().isEmpty() ? "todas" : filters.phasesOrEmpty().stream()
            .map(code -> names.getOrDefault(code.toUpperCase(Locale.ROOT), code)).collect(Collectors.joining(", "))));
        parts.add("Categorías: " + (filters.categoriesOrEmpty().isEmpty() ? "todas" : String.join(", ", filters.categoriesOrEmpty())));
        parts.add("Periodo: " + switch (periodMode) {
            case Filters.CONTENT_START -> "desde el inicio de cada contenido";
            case Filters.RANGE -> (filters.from() == null ? "inicio de cada contenido" : filters.from().toString()) + " a "
                + (filters.to() == null ? "hoy" : filters.to().toString());
            default -> "desde la entrada en el depósito actual";
        });
        return String.join(" · ", parts);
    }

    static PhaseRef ref(Phase phase, Map<String, String> categoryNames) {
        if (phase == null) return NO_PHASE;
        return new PhaseRef(phase.code(), phase.name(), phase.color(), phase.description(), rule(phase, categoryNames),
            phase.parameters());
    }

    /** "Categoría Mosto · FA activa, lenta o sin estado · FML cualquiera". */
    private static String rule(Phase phase, Map<String, String> categoryNames) {
        return "Categoría " + (phase.categories().isEmpty() ? "cualquiera"
                : phase.categories().stream().map(code -> categoryNames.getOrDefault(code, code)).collect(Collectors.joining(" o ")))
            + " · FA " + states(phase.alcoholic()) + " · FML " + states(phase.malolactic());
    }

    private static String states(List<String> states) {
        if (states.isEmpty()) return "cualquiera";
        List<String> labels = states.stream().map(code -> coop.miriv.enology.report.render.Html.state(code).toLowerCase(Locale.ROOT)).toList();
        if (labels.size() == 1) return labels.getFirst();
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " o " + labels.getLast();
    }

    private static Range range(Target target) {
        return target == null ? null : new Range(target.warnMin(), target.warnMax(), target.critMin(), target.critMax());
    }

    private static Target target(Range range) {
        return range == null ? null : new Target(null, null, null, null, range.warnMin(), range.warnMax(), range.critMin(), range.critMax());
    }

    /** Code or null for "no state": the target rule reads a null phase as "any". */
    private static String stateCode(String value) {
        String code = ReportPhaseService.stateOrNone(value);
        return ReportPhaseService.NO_STATE.equals(code) ? null : code;
    }

    private static String categoryCode(String name, Map<String, String> codesByName) {
        if (name == null || name.isBlank()) return null;
        return codesByName.get(name.toLowerCase(Locale.ROOT));
    }

    static int rank(String status) {
        return switch (status == null ? "NONE" : status) {
            case "CRIT" -> 4;
            case "WARN" -> 3;
            case "UNKNOWN" -> 2;
            case "OK" -> 1;
            default -> 0;
        };
    }

    private static Instant firstOf(Instant... values) {
        for (Instant value : values) if (value != null) return value;
        return Instant.now();
    }

    /** "D-2" before "D-10". */
    static int naturalCompare(String a, String b) {
        String[] x = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] y = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int cmp;
            if (x[i].matches("\\d+") && y[i].matches("\\d+")) {
                cmp = new BigDecimal(x[i]).compareTo(new BigDecimal(y[i]));
            } else {
                cmp = x[i].compareToIgnoreCase(y[i]);
            }
            if (cmp != 0) return cmp;
        }
        return Integer.compare(x.length, y.length);
    }

    private static Set<String> upper(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
