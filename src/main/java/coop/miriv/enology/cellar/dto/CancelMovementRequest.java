package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** F4-01: reason required to cancel a PLANNED movement. EXECUTED ones cannot be cancelled. */
public record CancelMovementRequest(
        @NotBlank @Size(max = 1000) String reason
) {}