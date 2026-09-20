package coop.miriv.enology.laboratory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Pasting a sheet of analyser readings: one row per sample, one mapped column per parameter. */
public final class ImportDto {

    private ImportDto() {}

    public record ImportRequest(@NotNull @Size(max = 500) @Valid List<ImportRow> rows) {}

    /**
     * @param reference caller-side row id (the grid's row number), echoed back so the UI can mark it
     * @param deposit   deposit code, the sheet's ID column
     * @param takenAt   sampling date and time in the cellar's timezone
     * @param values    parameter code → raw value as typed in the sheet (decimal comma allowed)
     */
    public record ImportRow(
        int reference,
        @NotBlank String deposit,
        @NotNull LocalDateTime takenAt,
        @NotNull Map<String, String> values,
        String observations
    ) {}

    /** What would happen (preview) or what happened (import) with one row. */
    public record ImportRowResult(
        int reference,
        String status,
        String message,
        String sampleCode,
        String contentCode,
        int parameters
    ) {
        public static final String OK = "OK";
        public static final String ERROR = "ERROR";
        public static final String DUPLICATE = "DUPLICATE";
    }

    public record ImportResponse(int imported, int skipped, List<ImportRowResult> rows) {}

    /** Saved column matching: the analyser always exports the same sheet. */
    public record TemplateColumn(@NotBlank String header, @NotBlank String target) {}

    public record TemplateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull @Size(max = 100) @Valid List<TemplateColumn> columns
    ) {}

    public record TemplateResponse(String name, List<TemplateColumn> columns, String author, java.time.Instant updatedAt) {}
}
