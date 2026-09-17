package coop.miriv.enology.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record CatalogItemRequest(
    @NotBlank String code,
    @NotBlank String name,
    String description
) {
}
