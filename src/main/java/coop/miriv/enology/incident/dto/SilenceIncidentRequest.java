package coop.miriv.enology.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SilenceIncidentRequest(@NotNull Instant until, @NotBlank String reason) {}
