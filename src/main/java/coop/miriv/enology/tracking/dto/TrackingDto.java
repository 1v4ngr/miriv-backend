package coop.miriv.enology.tracking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes of the tracking endpoints (series, events, overview, targets). */
public final class TrackingDto {

    private TrackingDto() {}

    public record ParameterInfo(String code, String name, String unit, int decimals) {}

    /** startedAt = first occupation of the content; the "days since start" axis is built from it. */
    public record ContentInfo(String code, String deposit, String lot, String category, Instant startedAt,
                              boolean ancestor, String descendantCode) {}

    public record Point(String content, String parameter, Instant takenAt, BigDecimal value, String qualifier,
                        BigDecimal limit, boolean validated, String sampleCode, String method) {}

    /** Resolved range for one content and parameter (null bounds are open). */
    public record TargetRange(String content, String parameter, BigDecimal warnMin, BigDecimal warnMax,
                              BigDecimal critMin, BigDecimal critMax) {}

    public record SeriesResponse(List<ContentInfo> contents, List<ParameterInfo> parameters, List<Point> points,
                                 List<TargetRange> targets) {}

    /** type is a stable code (TRANSFER, MIX, OPERATION, STATE_REVIEW…); label is ready to display. */
    public record Event(String content, Instant at, String type, String label, String detail) {}

    public record Reading(BigDecimal value, String qualifier, BigDecimal limit, Instant takenAt, String sampleCode,
                          Long daysAgo, String status) {}

    public record OverviewCell(String parameter, Reading latest, Reading previous, String trend) {}

    public record OverviewRow(String content, String deposit, String depositName, String zone, String lot,
                              String category, BigDecimal volumeLiters, String alcoholicState, String malolacticState,
                              int openSamples, Long daysSinceLastSample, int openTasks, Instant nextTaskDueAt,
                              String worstStatus, List<OverviewCell> cells) {}

    public record OverviewResponse(List<ParameterInfo> parameters, List<OverviewRow> rows) {}

    public record TargetView(UUID id, String parameter, String parameterName, String unit, String categoryCode,
                             String categoryName, String phase, BigDecimal warnMin, BigDecimal warnMax,
                             BigDecimal critMin, BigDecimal critMax, String note) {}

    public record TargetRequest(String parameter, String categoryCode, String phase, BigDecimal warnMin,
                                BigDecimal warnMax, BigDecimal critMin, BigDecimal critMax, String note) {}

    /** Latest reading of every parameter of one content, plus what the blend simulator needs. */
    public record LatestContent(String content, String deposit, BigDecimal depositUsefulCapacityLiters, String lot,
                                String categoryCode, String category, BigDecimal volumeLiters, String alcoholicState,
                                List<LatestReading> readings) {}

    public record LatestReading(String parameter, String name, String unit, int decimals, BigDecimal value,
                                String qualifier, BigDecimal limit, Instant takenAt, long daysAgo, boolean validated,
                                String sampleCode) {}
}
