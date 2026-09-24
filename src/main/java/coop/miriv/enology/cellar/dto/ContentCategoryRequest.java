package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Changes the category of the active content: {@code category} is the code or name of an internal category. */
public record ContentCategoryRequest(
    @NotBlank String category,
    @Size(max = 500) String reason
) {}
