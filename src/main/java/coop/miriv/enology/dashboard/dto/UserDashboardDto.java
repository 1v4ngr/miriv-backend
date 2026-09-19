package coop.miriv.enology.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Shapes of the personal tracking dashboards (F6-01). Not to be confused with the work-home DTOs. */
public final class UserDashboardDto {

    private UserDashboardDto() {}

    public record DashboardSummary(UUID id, String name, int position, boolean isDefault, Instant updatedAt) {}

    public record DashboardView(UUID id, String name, int position, boolean isDefault, int schemaVersion,
                                JsonNode layouts, JsonNode widgets, int version, Instant updatedAt) {}

    public record DashboardRequest(@NotBlank @Size(max = 80) String name, Integer schemaVersion,
                                   JsonNode layouts, JsonNode widgets, Integer version) {}
}
