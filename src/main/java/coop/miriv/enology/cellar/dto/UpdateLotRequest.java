package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UpdateLotRequest(
    @NotBlank String destination,
    @NotBlank String responsible,
    String origin,
    List<String> varieties
) {}