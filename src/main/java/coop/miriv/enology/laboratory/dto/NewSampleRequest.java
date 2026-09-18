package coop.miriv.enology.laboratory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record NewSampleRequest(
    @NotBlank String code,
    @NotBlank String originDeposit,
    @NotBlank String contentCode,
    String lotCode,
    String category,
    @NotNull LocalDateTime takenAt,
    @NotNull LocalDate takenDate,
    @NotBlank String panel,
    @NotBlank String responsible,
    String observations,
    String laboratoryCode
) {}
