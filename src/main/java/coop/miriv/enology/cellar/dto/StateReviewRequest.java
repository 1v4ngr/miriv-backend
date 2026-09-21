package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Confirms a fermentation state. When the alcoholic fermentation is closed as "Finalizada",
 * {@code newCategory} (code or name of an internal category) reclassifies the content — the must
 * becomes wine — in the same step.
 */
public record StateReviewRequest(
    @NotBlank String process,
    @NotBlank String decision,
    @NotBlank String reason,
    String newCategory
) {}
