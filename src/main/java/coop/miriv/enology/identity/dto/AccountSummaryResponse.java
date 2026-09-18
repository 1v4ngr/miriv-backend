package coop.miriv.enology.identity.dto;

import java.util.List;

public record AccountSummaryResponse(
    String username,
    String email,
    String displayName,
    String firstName,
    String lastName,
    String avatarUrl,
    String jobTitle,
    String centerCode,
    String centerName,
    List<RoleSummary> roles,
    List<PermissionSummary> permissions,
    List<String> zones,
    ReadScope readScope
) {
    public record RoleSummary(String code, String name, List<String> zones) {}
    public record PermissionSummary(String code, boolean allZones, List<String> zones) {}
    public record ReadScope(boolean allZones, List<String> zones) {}
}