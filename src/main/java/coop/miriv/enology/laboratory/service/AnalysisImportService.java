package coop.miriv.enology.laboratory.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.CodeGenerator;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportResponse;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRow;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportRowResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a pasted sheet of analyser readings: each row becomes a sample (resolved to the content that
 * occupied the deposit at the sampling time) plus its results, already validated. Rows that cannot be
 * imported — unknown deposit, empty tank at that time, bad number, duplicate — are reported one by one
 * and never block the rest, so the operator fixes them in the grid and imports again.
 *
 * `preview` runs exactly the same checks without writing, which is what feeds the grid's live warnings.
 */
@Service
public class AnalysisImportService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final CodeGenerator codes;
    private final AuditService audit;
    private final ZoneId timezone;

    public AnalysisImportService(JdbcTemplate jdbc, CurrentUserContext context, CodeGenerator codes,
                                 AuditService audit, @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.codes = codes;
        this.audit = audit;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public ImportResponse preview(ImportRequest request) {
        return run(request, false);
    }

    @Transactional
    public ImportResponse execute(ImportRequest request) {
        return run(request, true);
    }

    private ImportResponse run(ImportRequest request, boolean write) {
        UUID centerId = context.centerId();
        Map<String, Parameter> parameters = parameters();
        Map<String, DepositRef> deposits = new HashMap<>();
        List<ImportRowResult> results = new ArrayList<>();
        int imported = 0;
        int skipped = 0;

        for (ImportRow row : request.rows()) {
            ImportRowResult result = process(row, centerId, parameters, deposits, write);
            results.add(result);
            if (ImportRowResult.OK.equals(result.status())) imported++;
            else skipped++;
        }
        if (write && imported > 0) {
            audit.record("analysis_import", UUID.randomUUID(), "ANALYSES_IMPORTED",
                imported + " análisis importados, " + skipped + " filas omitidas");
        }
        return new ImportResponse(write ? imported : imported, skipped, results);
    }

    private ImportRowResult process(ImportRow row, UUID centerId, Map<String, Parameter> parameters,
                                    Map<String, DepositRef> deposits, boolean write) {
        String depositCode = row.deposit() == null ? "" : normalize(row.deposit());
        if (depositCode.isBlank()) return error(row, "Falta el depósito (columna ID).");
        if (row.takenAt() == null) return error(row, "Falta la fecha u hora de la toma.");

        DepositRef deposit = deposits.computeIfAbsent(depositCode, code -> jdbc.query(
            "select id, zone_id from deposit where center_id = ? and upper(code) = ? and active",
            (rs, index) -> new DepositRef(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class)),
            centerId, code).stream().findFirst().orElse(null));
        if (deposit == null) {
            return error(row, "El depósito " + depositCode + " no existe en el centro: créalo antes de importar esta fila.");
        }
        if (!context.hasInZone("RESULT_IMPORT", deposit.zoneId())) {
            return error(row, "No tienes permiso de importación en la zona del depósito " + depositCode + ".");
        }

        Instant takenAt = row.takenAt().atZone(timezone).toInstant();
        if (takenAt.isAfter(Instant.now())) return error(row, "La fecha de la toma está en el futuro.");

        List<Occupation> occupations = jdbc.query(
            "select o.id, o.content_unit_id, cu.code from occupation o "
                + "join content_unit cu on cu.id = o.content_unit_id "
                + "where o.deposit_id = ? and o.start_at <= ? and (o.end_at is null or o.end_at > ?) "
                + "order by o.start_at desc limit 1",
            (rs, index) -> new Occupation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
            deposit.id(), Timestamp.from(takenAt), Timestamp.from(takenAt));
        if (occupations.isEmpty()) {
            return error(row, "El depósito " + depositCode + " no tenía contenido en esa fecha: registra la entrada antes de importar.");
        }
        Occupation occupation = occupations.getFirst();

        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.values().entrySet()) {
            String raw = entry.getValue();
            if (raw == null || raw.isBlank()) continue;
            Parameter parameter = parameters.get(entry.getKey().trim().toUpperCase(Locale.ROOT));
            if (parameter == null) return error(row, "Parámetro desconocido: " + entry.getKey() + ".");
            BigDecimal value = number(raw);
            if (value == null) return error(row, "Valor no numérico en " + parameter.name() + ": «" + raw + "».");
            values.put(parameter.id(), value);
        }
        if (values.isEmpty()) return error(row, "La fila no trae ningún valor analítico.");

        Boolean duplicate = jdbc.queryForObject(
            "select exists(select 1 from sample where content_unit_id = ? and taken_at = ?)",
            Boolean.class, occupation.contentId(), Timestamp.from(takenAt));
        if (Boolean.TRUE.equals(duplicate)) {
            return new ImportRowResult(row.reference(), ImportRowResult.DUPLICATE,
                "Ya existe una muestra de " + occupation.contentCode() + " con esa fecha y hora.", null,
                occupation.contentCode(), values.size());
        }

        if (!write) {
            return new ImportRowResult(row.reference(), ImportRowResult.OK, null, null,
                occupation.contentCode(), values.size());
        }

        UUID actor = context.userId();
        // The CONTROL panel is exactly the analyser's worksheet (V17), so imported samples belong to it;
        // columns the sheet does not bring simply stay empty.
        UUID panelId = controlPanelId();
        String sampleCode = codes.next("MU", row.takenAt().getYear());
        UUID sampleId = UUID.randomUUID();
        jdbc.update("insert into sample(id, code, content_unit_id, occupation_id, deposit_id_at_sampling, "
                + "taken_at, taken_by_id, observations) values (?, ?, ?, ?, ?, ?, ?, ?)",
            sampleId, sampleCode, occupation.contentId(), occupation.occupationId(), deposit.id(),
            Timestamp.from(takenAt), actor, blankToNull(row.observations()));
        UUID analysisId = UUID.randomUUID();
        // Stored already validated: the reading comes from the analyser, not from manual entry.
        jdbc.update("insert into analysis(id, sample_id, panel_id, status, laboratory_name, validated_at, validated_by_id, "
                + "validation_note) values (?, ?, ?, 'VALIDATED'::analysis_status, ?, now(), ?, ?)",
            analysisId, sampleId, panelId, "Importación", actor, "Importado desde hoja de cálculo");
        for (Map.Entry<UUID, BigDecimal> entry : values.entrySet()) {
            jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, original_value, "
                    + "validated, validated_at, validated_by_id, created_by_id) "
                    + "values (?, ?, ?, 'NONE'::result_qualifier, ?, ?, true, now(), ?, ?)",
                UUID.randomUUID(), analysisId, entry.getKey(), entry.getValue(),
                entry.getValue().toPlainString(), actor, actor);
        }
        return new ImportRowResult(row.reference(), ImportRowResult.OK, null, sampleCode,
            occupation.contentCode(), values.size());
    }

    private UUID controlPanelId() {
        return jdbc.query("select id from analysis_panel where code = 'CONTROL'",
            (rs, index) -> rs.getObject(1, UUID.class)).stream().findFirst().orElse(null);
    }

    private Map<String, Parameter> parameters() {
        Map<String, Parameter> byCode = new HashMap<>();
        jdbc.query("select id, code, name from parameter", (RowCallbackHandler) rs ->
            byCode.put(rs.getString("code").toUpperCase(Locale.ROOT),
                new Parameter(rs.getObject("id", UUID.class), rs.getString("name"))));
        return byCode;
    }

    /** Accepts what a spreadsheet pastes: decimal comma or point, thousands separators and a trailing unit. */
    static BigDecimal number(String raw) {
        String cleaned = raw.trim().replace("°C", "").replace("º", "").replace("%", "").trim();
        cleaned = cleaned.replaceAll("[^0-9,.\\-+]", "");
        if (cleaned.isBlank()) return null;
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            // The rightmost separator is the decimal one; the other groups thousands.
            cleaned = lastComma > lastDot
                ? cleaned.replace(".", "").replace(',', '.')
                : cleaned.replace(",", "");
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static ImportRowResult error(ImportRow row, String message) {
        return new ImportRowResult(row.reference(), ImportRowResult.ERROR, message, null, null, 0);
    }

    private static String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record DepositRef(UUID id, UUID zoneId) {}
    private record Occupation(UUID occupationId, UUID contentId, String contentCode) {}
    private record Parameter(UUID id, String name) {}
}
