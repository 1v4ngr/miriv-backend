package coop.miriv.enology.laboratory.dto;

import jakarta.validation.constraints.NotBlank;

public record ReasonRequest(@NotBlank String reason) {}
