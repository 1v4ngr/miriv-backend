package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record DepositRequest(
    @NotBlank @Size(max = 30) String code,
    @NotBlank String center,
    @NotBlank String zone,
    @Size(max = 80) String position,
    @NotNull @DecimalMin("0.01") BigDecimal capacityLiters,
    @Size(max = 60) String material,
    boolean refrigerated
) {}
