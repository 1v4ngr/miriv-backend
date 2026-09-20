package coop.miriv.enology.laboratory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Move a sample to the deposit it was really taken from; the reason is kept in the audit log. */
public record ReassignDepositRequest(
    @NotBlank String deposit,
    @NotBlank @Size(max = 1000) String reason
) {}
