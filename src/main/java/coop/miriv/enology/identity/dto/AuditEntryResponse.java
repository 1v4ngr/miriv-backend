package coop.miriv.enology.identity.dto;
import java.time.Instant;
public record AuditEntryResponse(String entityName, String action, String author, String reason, Instant createdAt) {}
