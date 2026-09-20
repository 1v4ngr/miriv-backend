package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** Corrects when a movement happened (and optionally why), without touching volumes or deposits. */
public record RescheduleMovementRequest(
    @NotNull LocalDate effectiveDate,
    LocalTime effectiveTime,
    @Size(max = 1000) String reason
) {}
