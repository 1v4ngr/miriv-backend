package coop.miriv.enology.incident.dto;

import java.time.Instant;

public record IncidentEventResponse(String type, String note, String author, Instant createdAt) {}
