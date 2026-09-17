package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;

public record StateReviewRequest(@NotBlank String process, @NotBlank String decision, @NotBlank String reason) {}
