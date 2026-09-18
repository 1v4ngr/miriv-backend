package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;

public record ClearContentRequest(@NotBlank String reason, @NotBlank String responsible) {
}
