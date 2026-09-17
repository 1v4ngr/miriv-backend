package coop.miriv.enology.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(max = 120) String firstName,
    @Size(max = 160) String lastName,
    @Size(max = 120) String jobTitle,
    @Size(max = 2048) String avatarUrl,
    @Size(max = 30) String centerCode
) {}
