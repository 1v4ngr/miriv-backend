package coop.miriv.enology.assistant.mcp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Compact snapshot of one deposit for the AI assistant: what it holds, how every measured parameter
 * evolved over a window of days, and what is pending. Deliberately flat and free of nulls-heavy DTOs
 * so the model spends its context on data instead of on empty fields.
 */
public final class DepositStatusDto {

    private DepositStatusDto() {}

    public record DepositStatus(
        String deposit,
        String zone,
        String status,
        BigDecimal capacityLiters,
        boolean refrigerated,
        String content,
        String lot,
        String category,
        BigDecimal volumeLiters,
        Integer fillPercent,
        String alcoholicState,
        String malolacticState,
        Instant contentSince,
        Long daysInDeposit,
        int windowDays,
        List<ParameterTrend> parameters,
        List<String> alerts,
        List<String> recentEvents,
        List<String> notes
    ) {}

    /**
     * One parameter over the window: last value, how it moved, and the readings behind it.
     * `perDay` is the average change per day between the first and last reading of the window.
     */
    public record ParameterTrend(
        String parameter,
        String name,
        String unit,
        BigDecimal latest,
        Instant latestAt,
        Long daysAgo,
        String status,
        String target,
        int readings,
        BigDecimal change,
        BigDecimal perDay,
        String trend,
        List<Measurement> history
    ) {}

    public record Measurement(Instant at, BigDecimal value) {}

    /**
     * What the deposit detail screen shows, compact: the tank, its content, the elaboration phase (set by hand
     * or derived), the last analysis and the latest value of each parameter with its status and trend — no
     * reading-by-reading history (get_deposit_status has it).
     */
    public record DepositDetail(
        String deposit,
        String zone,
        String position,
        String material,
        String status,
        BigDecimal capacityLiters,
        boolean refrigerated,
        String content,
        String lot,
        String category,
        BigDecimal volumeLiters,
        Integer fillPercent,
        Instant contentSince,
        Instant lastSampleAt,
        Long daysSinceLastSample,
        Phase phase,
        String alcoholicState,
        String malolacticState,
        List<Reading> readings,
        List<String> alerts,
        List<String> notes
    ) {}

    /** Elaboration phase: {@code manual} when set by hand (then {@code automatic} is what the rules would say). */
    public record Phase(String name, String description, boolean manual, String automatic, Instant changedAt, String changedBy) {}

    public record Reading(String parameter, String name, String unit, BigDecimal latest, Instant latestAt, Long daysAgo,
                          String status, String target, BigDecimal perDay, String trend) {}
}
