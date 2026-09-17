package coop.miriv.enology.task.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelTaskRequest(@NotBlank String reason) {}
