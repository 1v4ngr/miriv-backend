package coop.miriv.enology.report.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything one cellar-status report shows, gathered once and then rendered twice (PDF and .xlsx), so both
 * files of a report always carry exactly the same data.
 */
public record CellarReport(Meta meta, List<PhaseCount> phaseCounts, List<DepositReport> deposits,
                           List<EmptyDeposit> emptyDeposits, List<AnalyticRow> rows, List<PhaseRef> phases,
                           List<Parameter> catalog) {

    /** Names for parameter codes, falling back to the code. */
    public String parameterNames(List<String> codes) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        catalog.forEach(parameter -> names.put(parameter.code(), parameter.name()));
        return String.join(", ", codes.stream().map(code -> names.getOrDefault(code, code)).toList());
    }

    /** from / to null = each deposit uses its own start (entry or content start) / now. */
    public record Meta(String code, String title, String centerName, String author, Instant generatedAt,
                       String periodMode, Instant from, Instant to, boolean includeProvisional, String scope,
                       String timezone) {}

    /** A phase as shown in the report; rule describes its criteria in words. The synthetic "sin fase" has code null. */
    public record PhaseRef(String code, String name, String color, String description, String rule,
                           List<String> parameters) {}

    public record PhaseCount(PhaseRef phase, int deposits, BigDecimal volumeLiters, int warn, int crit) {}

    public record Parameter(String code, String name, String unit, int decimals) {}

    /** A target range (null bounds are open). */
    public record Range(BigDecimal warnMin, BigDecimal warnMax, BigDecimal critMin, BigDecimal critMax) {}

    /** A stretch of time in which the content stayed in one phase, with the state that put it there. */
    public record Segment(PhaseRef phase, Instant from, Instant to, String categoryName, String alcoholicState,
                          String malolacticState) {}

    /** One current result, with the content's phase and the target that applied on the sampling date. */
    public record AnalyticRow(String deposit, String content, String lot, String depositAtSampling, String sampleCode,
                              Instant takenAt, Parameter parameter, BigDecimal value, String qualifier,
                              BigDecimal limit, String method, String laboratory, boolean validated,
                              Instant validatedAt, String validatedBy, String categoryName, PhaseRef phase,
                              String alcoholicState, String malolacticState, Range range, String status) {}

    public record Point(Instant at, BigDecimal value, String qualifier, BigDecimal limit, boolean validated,
                        String sampleCode, String status) {}

    /** One chart: a parameter over one phase segment, with the range that applied at the end of it. */
    public record Series(Parameter parameter, List<Point> points, Range range) {}

    /** The charts and the table of one phase segment. */
    public record Block(Segment segment, List<Series> series, List<Parameter> columns, List<TableRow> table) {}

    /** A sample (row) of a phase table: values by parameter code, in the block's column order. */
    public record TableRow(Instant takenAt, String sampleCode, boolean provisional, List<Cell> cells) {}

    public record Cell(BigDecimal value, String qualifier, BigDecimal limit, String status, boolean validated) {}

    public record Latest(Parameter parameter, AnalyticRow row, long daysAgo) {}

    public record Event(Instant at, String type, String label, String detail) {}

    public record Alert(String rule, String severity, Instant since, String detail) {}

    public record DepositReport(String deposit, String depositName, String zone, BigDecimal capacityLiters,
                                String content, String lot, String categoryCode, String categoryName,
                                BigDecimal volumeLiters, Integer fillPercent, String alcoholicState,
                                String malolacticState, String plan, Instant enteredDeposit, Instant contentStart,
                                Instant from, Instant to, PhaseRef phase, Instant phaseSince, List<Segment> segments,
                                List<Block> blocks, List<Latest> latest, String worstStatus, Instant lastSampleAt,
                                int sampleCount, List<Event> events, List<Alert> alerts) {}

    public record EmptyDeposit(String deposit, String depositName, String zone, BigDecimal capacityLiters,
                               String status) {}
}
