package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record MovementRequest(
    @NotBlank String type,
    @NotNull LocalDate effectiveDate,
    @NotNull LocalTime effectiveTime,
    @NotBlank String responsible,
    @NotBlank String reason,
    @NotBlank String sourceDeposit,
    String destinationDeposit,
    @NotNull @DecimalMin("0.01") BigDecimal volumeLiters,
    @NotNull @DecimalMin("0.00") BigDecimal lossLiters,
    String idempotencyKey,
    boolean authorizeMixture
) {}
