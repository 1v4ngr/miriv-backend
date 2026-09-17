package coop.miriv.enology.laboratory.dto;

import jakarta.validation.constraints.NotBlank;

public record CorrectionRequest(@NotBlank String value, @NotBlank String reason) {}
