package coop.miriv.enology.cellar.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record LineageEventResponse(String origin, String destination, Instant date,
                                   String movement, BigDecimal volumeLiters, String note) {}
