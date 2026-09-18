package coop.miriv.enology.task.dto;

import java.time.Instant;

public record TaskResponse(
    String code,
    String title,
    String depositCode,
    String contentCode,
    String responsible,
    String responsibleUsername,
    Instant dueAt,
    String priority,
    String status,
    String completionCriterion,
    String description,
    String samplePoint,
    String sampleCode,
    Instant executedAt,
    String result,
    String observations
) {}