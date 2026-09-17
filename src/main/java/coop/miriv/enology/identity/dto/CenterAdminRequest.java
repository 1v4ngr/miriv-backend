package coop.miriv.enology.identity.dto;
import jakarta.validation.constraints.NotBlank;
public record CenterAdminRequest(@NotBlank String code, @NotBlank String name) {}
