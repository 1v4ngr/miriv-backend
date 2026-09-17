package coop.miriv.enology.security.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(@NotBlank String usernameOrEmail) {}
