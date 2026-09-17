package coop.miriv.enology.laboratory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record ResultsRequest(
    @NotNull List<@Valid ResultInput> results,
    @NotBlank String status,
    @NotNull LocalDate processedAt,
    @NotBlank String laboratory,
    String equipment,
    @NotBlank String method,
    String observations
) {}
