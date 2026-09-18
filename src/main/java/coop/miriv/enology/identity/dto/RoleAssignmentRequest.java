package coop.miriv.enology.identity.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotBlank;

public record RoleAssignmentRequest(
    @NotBlank String roleCode,
    UUID zoneId
) {}