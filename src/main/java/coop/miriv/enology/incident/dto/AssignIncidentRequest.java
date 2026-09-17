package coop.miriv.enology.incident.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignIncidentRequest(@NotBlank String responsible) {}
