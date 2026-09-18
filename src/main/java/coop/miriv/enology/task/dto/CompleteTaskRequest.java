package coop.miriv.enology.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CompleteTaskRequest(@NotBlank String result, String observations, String sampleCode,
                                  Instant executedAt, @Size(max = 200) String samplePoint) {}