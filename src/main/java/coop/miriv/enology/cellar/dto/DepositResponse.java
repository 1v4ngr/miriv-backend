package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DepositResponse(
    UUID id,
    String code,
    String center,
    String zone,
    String position,
    BigDecimal capacityLiters,
    BigDecimal nominalCapacityLiters,
    String material,
    boolean refrigerated,
    String status,
    String priority,
    List<OccupationResponse> occupations,
    List<CleaningRecordResponse> cleaningHistory
) {}
