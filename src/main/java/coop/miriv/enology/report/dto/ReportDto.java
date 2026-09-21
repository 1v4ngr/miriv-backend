package coop.miriv.enology.report.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the report endpoints (report phases, report jobs, form options). */
public final class ReportDto {

    private ReportDto() {}

    // ------------------------------------------------------------------ phases (Administración)

    /** Empty criteria lists mean "any"; NONE in a state list matches a content with no state recorded. */
    public record PhaseView(UUID id, String code, String name, String description, String color, int position,
                            boolean active, List<String> categoryCodes, List<String> alcoholicStates,
                            List<String> malolacticStates, List<String> parameterCodes) {}

    public record PhaseRequest(@Size(max = 40) String code, @NotBlank @Size(max = 120) String name,
                               @Size(max = 500) String description, @Size(max = 9) String color, Boolean active,
                               List<String> categoryCodes, List<String> alcoholicStates, List<String> malolacticStates,
                               List<String> parameterCodes) {}

    public record PhaseOrderRequest(@NotNull List<UUID> ids) {}

    // ------------------------------------------------------------------ report jobs

    /**
     * Scope of a cellar report. Empty lists mean "all". period: DEPOSIT_ENTRY (default, since the content entered
     * its current deposit), CONTENT_START (since the content first entered the cellar) or RANGE (from / to, business
     * days in Europe/Madrid; either may be empty).
     */
    public record Filters(List<String> zones, List<String> deposits, List<String> phases, List<String> categories,
                          String period, LocalDate from, LocalDate to) {

        public static final String DEPOSIT_ENTRY = "DEPOSIT_ENTRY";
        public static final String CONTENT_START = "CONTENT_START";
        public static final String RANGE = "RANGE";

        public static Filters empty() {
            return new Filters(List.of(), List.of(), List.of(), List.of(), DEPOSIT_ENTRY, null, null);
        }

        public String periodOrDefault() {
            if (period == null || period.isBlank()) return from != null || to != null ? RANGE : DEPOSIT_ENTRY;
            return period.trim().toUpperCase(java.util.Locale.ROOT);
        }

        public List<String> zonesOrEmpty() { return zones == null ? List.of() : zones; }
        public List<String> depositsOrEmpty() { return deposits == null ? List.of() : deposits; }
        public List<String> phasesOrEmpty() { return phases == null ? List.of() : phases; }
        public List<String> categoriesOrEmpty() { return categories == null ? List.of() : categories; }
    }

    public record CreateReportRequest(@NotBlank String type, @Size(max = 200) String title, @Valid Filters filters,
                                      boolean includeProvisional) {}

    public record JobView(String code, String type, String title, Filters filters, String scope,
                          boolean includeProvisional, String status, String error, Integer recordCount,
                          Integer depositCount, String author, Instant createdAt, Instant finishedAt, boolean pdf,
                          boolean xlsx) {}

    // ------------------------------------------------------------------ form options

    public record Option(String code, String name) {}

    /** A deposit that can be picked, with what it holds now (null when empty) and its current phase. */
    public record DepositOption(String code, String name, String zone, String content, String category,
                                String phase) {}

    public record Options(List<Option> zones, List<DepositOption> deposits, List<Option> categories,
                          List<PhaseView> phases) {}
}
