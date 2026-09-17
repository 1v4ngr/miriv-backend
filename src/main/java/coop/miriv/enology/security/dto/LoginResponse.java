package coop.miriv.enology.security.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
    String accessToken,
    long expiresInSeconds,
    UUID userId,
    String fullName,
    List<String> roles
) {
}
