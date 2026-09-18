package coop.miriv.enology.incident.dto;

import java.time.Instant;
import java.util.List;

public record IncidentResponse(
    String code,
    String title,
    String depositCode,
    String contentCode,
    String priority,
    String status,
    String responsible,
    String responsibleUsername,
    Instant openedAt,
    Instant silencedUntil,
    String resolution,
    String resolutionReason,
    List<IncidentEventResponse> events,
    List<String> evidence
) {}
