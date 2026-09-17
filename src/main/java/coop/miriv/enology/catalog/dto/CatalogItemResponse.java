package coop.miriv.enology.catalog.dto;

import java.util.UUID;

public record CatalogItemResponse(
    UUID id,
    String code,
    String name,
    String description,
    boolean active
) {
}
