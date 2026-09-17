package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;

public record MovementResponse(
    String code,
    boolean becameMixture,
    BigDecimal sourceFinalLiters,
    String destinationContentCode,
    BigDecimal destinationFinalLiters
) {}
