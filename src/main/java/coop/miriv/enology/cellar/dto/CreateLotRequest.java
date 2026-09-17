package coop.miriv.enology.cellar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateLotRequest(@Valid @NotNull LotRequest lot, @Valid LotEntryRequest entry) {}
