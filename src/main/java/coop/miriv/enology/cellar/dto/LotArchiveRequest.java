package coop.miriv.enology.cellar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LotArchiveRequest(@NotBlank @Size(max = 1000) String reason) {
}
