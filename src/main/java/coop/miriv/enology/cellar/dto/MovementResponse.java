package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MovementResponse(
    String code,
    boolean becameMixture,
    BigDecimal sourceFinalLiters,
    String destinationContentCode,
    BigDecimal destinationFinalLiters,
    /** F4-01: status of the registered movement (PLANNED if saved without execution). */
    String status,
    /** F4-01: effective time as stored (planned movements reuse the wizard's date/time). */
    Instant effectiveAt
) {
    /** Backwards-compatible factory for the existing register() callers that ignore status/time. */
    public static MovementResponse of(String code, boolean becameMixture, BigDecimal sourceFinal,
                                       String destinationContentCode, BigDecimal destinationFinal,
                                       String status, Instant effectiveAt) {
        return new MovementResponse(code, becameMixture, sourceFinal, destinationContentCode, destinationFinal, status, effectiveAt);
    }
}
