package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateLotRequest(
    @NotBlank String destination,
    @NotBlank String responsible,
    String origin,
    String variety
) {}
