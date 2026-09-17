package coop.miriv.enology.plan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PlanVersionRequest(
    String alcoholicStrategy,
    String yeast,
    LocalDate inoculationDate,
    @DecimalMin("0.00") BigDecimal sugarTargetGramsPerLiter,
    @NotBlank String malolacticIntent,
    String malolacticStrategy,
    String bacteria,
    LocalDate malolacticExpectedAt,
    BigDecimal temperatureMinCelsius,
    BigDecimal temperatureMaxCelsius,
    String samplingPanel,
    @Min(1) Integer samplingFrequencyDays,
    @NotBlank String reason,
    @NotNull Instant effectiveAt
) {}
