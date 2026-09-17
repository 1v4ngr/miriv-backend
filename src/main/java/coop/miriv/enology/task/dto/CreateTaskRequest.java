package coop.miriv.enology.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateTaskRequest(
    @NotBlank String title,
    @NotBlank String depositCode,
    String contentCode,
    @NotBlank String responsible,
    @NotNull Instant dueAt,
    @NotBlank String priority,
    String completionCriterion
) {}
