package coop.miriv.enology.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record PermissionGrantRequest(
    @NotBlank String permissionCode,
    UUID zoneId,
    @NotBlank @Size(min = 3, max = 1000) String reason,
    Instant validFrom,
    Instant validUntil
) {}