package coop.miriv.enology.cellar.dto;

import java.time.Instant;
import java.util.List;

/** F4-01: full snapshot of a movement (UI15). */
public record MovementDetailResponse(
        String code,
        String type,
        String status,
        Instant effectiveAt,
        Instant registeredAt,
        String responsible,
        String registeredBy,
        String reason,
        String cancelledReason,
        List<MovementLineResponse> lines
) {}