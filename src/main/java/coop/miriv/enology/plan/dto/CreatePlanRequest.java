package coop.miriv.enology.plan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePlanRequest(@NotBlank String name, String destination,
                                @Valid @NotNull PlanVersionRequest version) {}
