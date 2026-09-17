package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

public record CompleteCleaningRequest(@NotBlank @Size(max = 120) String action,
                                      @NotBlank @Size(max = 200) String result,
                                      @Size(max = 1000) String notes,
                                      @NotNull Boolean approved) {}
