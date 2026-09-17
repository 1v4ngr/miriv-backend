package coop.miriv.enology.laboratory.dto;

import java.time.LocalDate;
import java.util.List;

public record SampleResponse(
    String code,
    String originDeposit,
    String currentDeposit,
    String contentCode,
    String lotCode,
    String category,
    String takenAt,
    LocalDate takenDate,
    String age,
    String panel,
    int completed,
    int required,
    String status,
    String responsible,
    boolean overdue,
    List<SampleResultResponse> results,
    String observations,
    LocalDate processedAt,
    String laboratory,
    String equipment,
    String method,
    String validationNote
) {}
