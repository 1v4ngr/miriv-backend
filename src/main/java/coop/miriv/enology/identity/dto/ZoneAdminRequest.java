package coop.miriv.enology.identity.dto;
import jakarta.validation.constraints.NotBlank;
public record ZoneAdminRequest(@NotBlank String code,@NotBlank String name,@NotBlank String centerCode) {}
