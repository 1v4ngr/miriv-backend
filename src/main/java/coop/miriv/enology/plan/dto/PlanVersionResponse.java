package coop.miriv.enology.plan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PlanVersionResponse(
    int number,
    String alcoholicStrategy,
    String yeast,
    LocalDate inoculationDate,
    BigDecimal sugarTargetGramsPerLiter,
    String malolacticIntent,
    String malolacticStrategy,
    String bacteria,
    LocalDate malolacticExpectedAt,
    BigDecimal temperatureMinCelsius,
    BigDecimal temperatureMaxCelsius,
    String samplingPanel,
    Integer samplingFrequencyDays,
    String author,
    String reason,
    Instant effectiveAt
) {}
