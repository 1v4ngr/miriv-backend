package coop.miriv.enology.incident.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveIncidentRequest(@NotBlank String reason, String discardCategory) {}
