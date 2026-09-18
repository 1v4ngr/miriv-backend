package coop.miriv.enology.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserAccountStatusRequest(
    @NotBlank @Size(min = 3, max = 500) String reason
) {}