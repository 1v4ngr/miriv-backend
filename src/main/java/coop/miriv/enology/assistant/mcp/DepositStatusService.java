package coop.miriv.enology.assistant.mcp;

import coop.miriv.enology.assistant.mcp.DepositStatusDto.DepositStatus;
import coop.miriv.enology.assistant.mcp.DepositStatusDto.Measurement;
import coop.miriv.enology.assistant.mcp.DepositStatusDto.ParameterTrend;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.dto.OccupationResponse;
import coop.miriv.enology.cellar.service.DepositService;
import coop.miriv.enology.tracking.dto.TrackingDto.Event;
import coop.miriv.enology.tracking.dto.TrackingDto.LatestContent;
import coop.miriv.enology.tracking.dto.TrackingDto.LatestReading;
import coop.miriv.enology.tracking.dto.TrackingDto.ParameterInfo;
import coop.miriv.enology.tracking.dto.TrackingDto.Point;
import coop.miriv.enology.tracking.dto.TrackingDto.SeriesResponse;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetRange;
import coop.miriv.enology.tracking.service.AlertService;
import coop.miriv.enology.tracking.service.TrackingService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the snapshot behind the `get_deposit_status` MCP tool: one call gives the model the tank,
 * its content, every parameter's trend over a window of days, and the pending work. Everything is
 * read through the existing services, so the caller's center and readable zones still apply.
 */
@Service
public class DepositStatusService {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 365;
    private static final int MAX_POINTS_PER_PARAMETER = 20;
    private static final int MAX_EVENTS = 12;
    private static final MathContext RATE = new MathContext(3, RoundingMode.HALF_UP);

    private final DepositService deposits;
    private final TrackingService tracking;
    private final AlertService alerts;
    private final DateTimeFormatter dayFormat;

    public DepositStatusService(DepositService deposits, TrackingService tracking, AlertService alerts,
                                @Value("${app.timezone}") String timezone) {
        this.deposits = deposits;
        this.tracking = tracking;
        this.alerts = alerts;
        this.dayFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.of("es", "ES")).withZone(ZoneId.of(timezone));
    }

    @Transactional(readOnly = true)
    public DepositStatus status(String depositCode, Integer days) {
        int window = days == null || days <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(days, MAX_WINDOW_DAYS);
        DepositResponse deposit = deposits.get(depositCode);
        OccupationResponse occupation = deposit.occupations().stream()
            .filter(item -> item.exitDate() == null)
            .findFirst()
            .orElse(null);

        List<String> notes = new ArrayList<>();
        String code = deposit.code();
        if (occupation == null) {
            notes.add("El depósito no tiene contenido activo: no hay analíticas en curso.");
            return new DepositStatus(code, deposit.zone(), deposit.status(), deposit.capacityLiters(),
                deposit.refrigerated(), null, null, null, null, null, null, null, null, null, window,
                List.of(), alertsFor(code), List.of(), notes);
        }

        String content = occupation.contentCode();
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(window));

        List<LatestContent> latest = tracking.latest(List.of(content));
        List<LatestReading> readings = latest.isEmpty() ? List.of() : latest.getFirst().readings();
        List<ParameterTrend> parameters = trends(content, readings, from, now, window, notes);

        List<String> events = tracking.events(List.of(content), from, now).stream()
            .sorted(Comparator.comparing(Event::at).reversed())
            .limit(MAX_EVENTS)
            .map(event -> dayFormat.format(event.at()) + " · " + event.label()
                + (event.detail() == null || event.detail().isBlank() ? "" : " (" + event.detail() + ")"))
            .toList();

        Long days_ = occupation.entryDate() == null ? null : Duration.between(occupation.entryDate(), now).toDays();
        Integer fill = deposit.capacityLiters() == null || deposit.capacityLiters().signum() == 0
            || occupation.volumeLiters() == null ? null
            : occupation.volumeLiters().multiply(BigDecimal.valueOf(100))
                .divide(deposit.capacityLiters(), 0, RoundingMode.HALF_UP).intValue();

        if (parameters.isEmpty()) notes.add("Sin analíticas registradas en los últimos " + window + " días.");

        return new DepositStatus(code, deposit.zone(), deposit.status(), deposit.capacityLiters(),
            deposit.refrigerated(), content, occupation.lotCode(), occupation.category(), occupation.volumeLiters(),
            fill, occupation.alcoholicState(), occupation.malolacticState(), occupation.entryDate(), days_, window,
            parameters, alertsFor(code), events, notes);
    }

    /** One trend per parameter that has a current reading, with its points inside the window. */
    private List<ParameterTrend> trends(String content, List<LatestReading> readings, Instant from, Instant to,
                                        int window, List<String> notes) {
        if (readings.isEmpty()) return List.of();
        List<String> codes = readings.stream().map(LatestReading::parameter).distinct().toList();
        SeriesResponse series = tracking.series(List.of(content), codes, from, to, false);

        Map<String, List<Point>> pointsByParameter = new LinkedHashMap<>();
        for (Point point : series.points()) {
            if (point.value() == null) continue;
            pointsByParameter.computeIfAbsent(point.parameter(), key -> new ArrayList<>()).add(point);
        }
        Map<String, ParameterInfo> catalog = new LinkedHashMap<>();
        series.parameters().forEach(parameter -> catalog.put(parameter.code(), parameter));
        Map<String, TargetRange> targets = new LinkedHashMap<>();
        series.targets().forEach(target -> targets.put(target.parameter(), target));

        List<ParameterTrend> trends = new ArrayList<>();
        for (LatestReading reading : readings) {
            ParameterInfo info = catalog.get(reading.parameter());
            List<Point> points = pointsByParameter.getOrDefault(reading.parameter(), List.of());
            List<Measurement> history = history(points, info);
            BigDecimal first = history.isEmpty() ? null : history.getFirst().value();
            BigDecimal last = history.isEmpty() ? reading.value() : history.getLast().value();
            BigDecimal change = first == null || last == null || history.size() < 2 ? null : last.subtract(first);
            BigDecimal perDay = perDay(change, history);
            trends.add(new ParameterTrend(reading.parameter(),
                info == null ? reading.name() : info.name(),
                info == null ? reading.unit() : info.unit(),
                round(reading.value(), info), reading.takenAt(), reading.daysAgo(),
                status(reading.value(), targets.get(reading.parameter())),
                describe(targets.get(reading.parameter())),
                history.size(), change == null ? null : change.round(RATE), perDay,
                trend(change), history));
        }
        long stale = trends.stream().filter(trend -> trend.daysAgo() != null && trend.daysAgo() > window).count();
        if (stale > 0) notes.add(stale + " parámetro(s) sin medir dentro de la ventana: su último valor es anterior.");
        return trends;
    }

    /** Keeps the window's readings, thinning older ones when there are too many; the last one always stays. */
    private List<Measurement> history(List<Point> points, ParameterInfo info) {
        List<Measurement> all = points.stream()
            .sorted(Comparator.comparing(Point::takenAt))
            .map(point -> new Measurement(point.takenAt(), round(point.value(), info)))
            .toList();
        if (all.size() <= MAX_POINTS_PER_PARAMETER) return all;
        int drop = all.size() - MAX_POINTS_PER_PARAMETER;
        List<Measurement> kept = new ArrayList<>(all.subList(drop, all.size()));
        kept.addFirst(all.getFirst());
        return kept;
    }

    private static BigDecimal perDay(BigDecimal change, List<Measurement> history) {
        if (change == null || history.size() < 2) return null;
        long hours = Duration.between(history.getFirst().at(), history.getLast().at()).toHours();
        if (hours < 1) return null;
        return change.multiply(BigDecimal.valueOf(24))
            .divide(BigDecimal.valueOf(hours), RATE);
    }

    private static String trend(BigDecimal change) {
        if (change == null) return "unknown";
        int sign = change.signum();
        return sign > 0 ? "rising" : sign < 0 ? "falling" : "stable";
    }

    /** OK / WARNING / CRITICAL against the resolved target, mirroring what the tracking UI shows. */
    private static String status(BigDecimal value, TargetRange target) {
        if (value == null || target == null) return null;
        if (below(value, target.critMin()) || above(value, target.critMax())) return "CRITICAL";
        if (below(value, target.warnMin()) || above(value, target.warnMax())) return "WARNING";
        return "OK";
    }

    private static boolean below(BigDecimal value, BigDecimal bound) { return bound != null && value.compareTo(bound) < 0; }

    private static boolean above(BigDecimal value, BigDecimal bound) { return bound != null && value.compareTo(bound) > 0; }

    private static String describe(TargetRange target) {
        if (target == null) return null;
        String warn = range(target.warnMin(), target.warnMax());
        String crit = range(target.critMin(), target.critMax());
        if (warn == null && crit == null) return null;
        return (warn == null ? "" : "objetivo " + warn) + (warn != null && crit != null ? "; " : "")
            + (crit == null ? "" : "crítico fuera de " + crit);
    }

    private static String range(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) return null;
        if (min == null) return "≤ " + max.toPlainString();
        if (max == null) return "≥ " + min.toPlainString();
        return min.toPlainString() + "–" + max.toPlainString();
    }

    private static BigDecimal round(BigDecimal value, ParameterInfo info) {
        if (value == null) return null;
        return info == null ? value : value.setScale(info.decimals(), RoundingMode.HALF_UP);
    }

    private List<String> alertsFor(String deposit) {
        return alerts.alerts().stream()
            .filter(alert -> deposit.equalsIgnoreCase(alert.deposit()))
            .map(alert -> alert.severity() + " · " + alert.rule()
                + (alert.detail() == null || alert.detail().isBlank() ? "" : ": " + alert.detail())
                + (alert.since() == null ? "" : " (desde " + dayFormat.format(alert.since()) + ")"))
            .toList();
    }
}
