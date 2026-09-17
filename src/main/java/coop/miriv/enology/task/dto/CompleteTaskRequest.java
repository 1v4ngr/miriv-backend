package coop.miriv.enology.task.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteTaskRequest(@NotBlank String result, String observations, String sampleCode) {}
