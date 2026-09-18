package coop.miriv.enology.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserAccountResponse(
    UUID id,
    String username,
    String email,
    String firstName,
    String lastName,
    String displayName,
    String jobTitle,
    String avatarUrl,
    boolean active,
    Instant createdAt,
    Instant deactivatedAt,
    List<RoleSummary> roles,
    List<PermissionGrantSummary> grants,
    List<CenterMembershipSummary> centers
) {
    public record RoleSummary(UUID roleId, String code, String name, UUID zoneId, String zoneCode, String zoneName) {}
    public record PermissionGrantSummary(UUID id, String permissionCode, UUID zoneId, String zoneCode,
                                         UUID grantedBy, String grantedByUsername, String reason,
                                         Instant validFrom, Instant validUntil) {}
    public record CenterMembershipSummary(UUID centerId, String code, String name) {}
}