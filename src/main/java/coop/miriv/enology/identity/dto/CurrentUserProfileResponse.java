package coop.miriv.enology.identity.dto;

import java.util.List;

public record CurrentUserProfileResponse(
    String username,
    String email,
    String firstName,
    String lastName,
    String displayName,
    String avatarUrl,
    String jobTitle,
    String centerCode,
    String centerName,
    List<String> zones
) {}
