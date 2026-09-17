package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record LotEntryRequest(
    @NotBlank String depositCode,
    @NotNull @DecimalMin("0.01") BigDecimal volumeLiters,
    @NotNull Instant effectiveDate
) {}
