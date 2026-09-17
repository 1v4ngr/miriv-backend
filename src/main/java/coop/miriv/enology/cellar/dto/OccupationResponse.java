package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OccupationResponse(
    String contentCode,
    String lotCode,
    Instant entryDate,
    Instant exitDate,
    BigDecimal volumeLiters,
    String category,
    String alcoholicState,
    String malolacticState
) {}
