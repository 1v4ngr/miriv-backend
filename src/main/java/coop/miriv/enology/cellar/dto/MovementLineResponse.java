package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;

/** F4-01: one row of a movement detail (UI15). {@code *_before/_after} may be null on legacy data. */
public record MovementLineResponse(
        String sourceDeposit,
        String sourceContent,
        String destinationDeposit,
        String destinationContent,
        BigDecimal volumeLiters,
        BigDecimal lossLiters,
        BigDecimal sourceBefore,
        BigDecimal sourceAfter,
        BigDecimal destinationBefore,
        BigDecimal destinationAfter
) {}