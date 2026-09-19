package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** F4-01: lightweight row in the movements list (UI14). */
public record MovementSummaryResponse(
        String code,
        String type,
        String status,
        Instant effectiveAt,
        Instant registeredAt,
        List<String> sourceDeposits,
        List<String> destinationDeposits,
        List<String> lotCodes,
        BigDecimal volumeLiters,
        String responsible,
        String registeredBy
) {}