package coop.miriv.enology.blend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Shapes of the saved blend simulations (F7-04). */
public final class BlendDto {

    private BlendDto() {}

    public record BlendSummary(UUID id, String name, String status, String destinationDepositCode, String author,
                               Instant updatedAt) {}

    public record BlendView(UUID id, String name, String status, String destinationDepositCode, JsonNode payload,
                            JsonNode result, String author, int version, Instant updatedAt,
                            java.util.List<String> plannedMovements) {}

    public record BlendRequest(@NotBlank @Size(max = 120) String name, @Size(max = 40) String destinationDepositCode,
                               @NotNull JsonNode payload, JsonNode result, Integer version) {}
}