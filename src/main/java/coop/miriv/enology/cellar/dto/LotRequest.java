package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record LotRequest(
    @NotBlank String code,
    @NotNull @Min(2000) Integer campaign,
    @NotBlank String category,
    @NotBlank String destination,
    @NotBlank String responsible,
    @NotNull LocalDate entryDate,
    String origin,
    List<String> varieties
) {}