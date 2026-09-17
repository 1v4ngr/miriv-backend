package coop.miriv.enology.laboratory.dto;

import jakarta.validation.constraints.NotBlank;

public record ResultInput(@NotBlank String parameter, String value, String unit, String qualifier, String limit) {}
