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
    boolean authorizeMixture,
    // F2-06: client-supplied expected balances; if they differ from the current
    // balances, the server responds 409 STALE_BALANCE instead of writing.
    BigDecimal expectedSourceLiters,
    BigDecimal expectedDestinationLiters,
    // F4-01: if true, the movement is registered as PLANNED: no occupation/content_unit
    // changes, the body is stored as planned_request for later execution. Requires
    // MOVEMENT_PLAN permission; capacity and stale-balance checks are skipped.
    boolean planned
) {}
