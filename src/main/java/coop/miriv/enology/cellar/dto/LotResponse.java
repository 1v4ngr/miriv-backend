package coop.miriv.enology.cellar.dto;

import java.time.LocalDate;
import java.util.List;

public record LotResponse(
    String code,
    int campaign,
    String category,
    String destination,
    String responsible,
    LocalDate entryDate,
    String origin,
    String variety,
    boolean archived,
    List<String> contentCodes
) {}
