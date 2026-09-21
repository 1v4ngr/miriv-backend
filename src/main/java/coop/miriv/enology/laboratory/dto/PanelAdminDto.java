package coop.miriv.enology.laboratory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Administración → Laboratorio: the parameter catalogue and the analysis templates built from it. */
public final class PanelAdminDto {

    private PanelAdminDto() {}

    public record ParameterView(String code, String name, String unit, int decimals, BigDecimal plausibilityMin,
                                BigDecimal plausibilityMax, String description, boolean active, int panels) {}

    public record ParameterRequest(
        @Pattern(regexp = "[A-Z0-9_]{2,60}", message = "Código en mayúsculas, números o _ (2–60).") String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Size(max = 40) String unit,
        @Min(0) @Max(6) int decimals,
        BigDecimal plausibilityMin,
        BigDecimal plausibilityMax,
        @Size(max = 1000) String description,
        Boolean active
    ) {}

    public record PanelParameter(String code, String name, String unit, boolean required) {}

    public record PanelCategory(String code, String name, boolean isDefault) {}

    public record PanelView(String code, String name, String description, boolean active,
                            List<PanelParameter> parameters, List<PanelCategory> categories, int samples) {}

    public record PanelCreateRequest(
        @Pattern(regexp = "[A-Z0-9_]{2,60}", message = "Código en mayúsculas, números o _ (2–60).") String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description
    ) {}

    /** Replaces the template as a whole: its parameters (in order) and the categories it serves. */
    public record PanelUpdateRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        boolean active,
        @NotNull @Size(max = 80) @Valid List<ParameterRef> parameters,
        @NotNull @Valid List<CategoryRef> categories
    ) {}

    public record ParameterRef(@NotBlank String code, boolean required) {}

    public record CategoryRef(@NotBlank String code, boolean isDefault) {}
}
