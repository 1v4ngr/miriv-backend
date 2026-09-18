package coop.miriv.enology.laboratory.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.laboratory.dto.CorrectionRequest;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.PanelParameterResponse;
import coop.miriv.enology.laboratory.dto.ResultInput;
import coop.miriv.enology.laboratory.dto.ResultVersionResponse;
import coop.miriv.enology.laboratory.dto.ResultsRequest;
import coop.miriv.enology.laboratory.dto.SampleResponse;
import coop.miriv.enology.laboratory.dto.SampleResultResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LaboratoryService {

    private static final Map<String, String> PARAMETER_ALIASES = Map.ofEntries(
        Map.entry("SO₂ libre", "FREE_SO2"), Map.entry("SO2 libre", "FREE_SO2"),
        Map.entry("SO₂ total", "TOTAL_SO2"), Map.entry("SO2 total", "TOTAL_SO2"),
        Map.entry("Temperatura", "CONTENT_TEMPERATURE"),
        Map.entry("Alcohol probable", "POTENTIAL_ALCOHOL"),
        Map.entry("Ácido málico", "L_MALIC_ACID"),
        Map.entry("Ácido láctico", "L_LACTIC_ACID"));

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ZoneId timezone;

    public LaboratoryService(JdbcTemplate jdbc, CurrentUserContext context,
                             @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public List<SampleResponse> list() {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT + " where d.center_id = ? order by s.taken_at desc",
            (rs, index) -> sampleRow(rs), context.centerId());
        return rows.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SampleResponse get(String code) { return response(find(code, context.centerId())); }

    @Transactional
    public SampleResponse create(NewSampleRequest request) {
        UUID centerId = context.centerId();
        String code = normalize(request.code());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sample where code = ?)", Boolean.class, code))) {
            throw new ConflictException("A sample with this code already exists.");
        }
        if (!request.takenDate().equals(request.takenAt().toLocalDate())) {
            throw new BusinessRuleException("Sampling date and timestamp differ.");
        }
        Instant takenAt = request.takenAt().atZone(timezone).toInstant();
        if (takenAt.isAfter(Instant.now())) throw new BusinessRuleException("Sampling time cannot be in the future.");
        List<OccupationAtTime> occupations = jdbc.query(
            "select o.id, o.content_unit_id, cu.code as content_code, l.code as lot_code "
                + "from occupation o join deposit d on d.id = o.deposit_id "
                + "join content_unit cu on cu.id = o.content_unit_id join lot l on l.id = cu.lot_id "
                + "where d.center_id = ? and d.code = ? and o.start_at <= ? and (o.end_at is null or o.end_at > ?) "
                + "order by o.start_at desc limit 1",
            (rs, index) -> new OccupationAtTime(rs.getObject("id", UUID.class),
                rs.getObject("content_unit_id", UUID.class), rs.getString("content_code"), rs.getString("lot_code")),
            centerId, normalize(request.originDeposit()), Timestamp.from(takenAt), Timestamp.from(takenAt));
        if (occupations.isEmpty()) throw new BusinessRuleException("No content occupied the deposit at the sampling time.");
        OccupationAtTime occupation = occupations.getFirst();
        if (!occupation.contentCode().equalsIgnoreCase(request.contentCode())) {
            throw new BusinessRuleException("Sample content does not match the historical occupation.");
        }
        if (request.lotCode() != null && !request.lotCode().isBlank()
            && !occupation.lotCode().equalsIgnoreCase(request.lotCode())) {
            throw new BusinessRuleException("Sample lot does not match the historical occupation.");
        }
        UUID depositId = jdbc.queryForObject("select id from deposit where center_id = ? and code = ?", UUID.class,
            centerId, normalize(request.originDeposit()));
        UUID responsibleId = userId(request.responsible(), centerId);
        UUID panelId = panelId(request.panel());
        UUID sampleId = UUID.randomUUID();
        jdbc.update("insert into sample(id, code, content_unit_id, occupation_id, deposit_id_at_sampling, "
                + "taken_at, taken_by_id, observations) values (?, ?, ?, ?, ?, ?, ?, ?)", sampleId, code,
            occupation.contentId(), occupation.occupationId(), depositId, Timestamp.from(takenAt), responsibleId,
            blankToNull(request.observations()));
        jdbc.update("insert into analysis(id, sample_id, panel_id, status) values (?, ?, ?, 'DRAFT'::analysis_status)",
            UUID.randomUUID(), sampleId, panelId);
        return get(code);
    }

    @Transactional
    public SampleResponse saveResults(String code, ResultsRequest request) {
        SampleRow sample = findForUpdate(code);
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Invalidated analysis cannot be edited.");
        if (sample.status().equals("VALIDATED")) throw new BusinessRuleException("Use correction to change a validated result.");
        for (ResultInput input : request.results()) {
            if ((input.value() == null || input.value().isBlank())
                && (input.qualifier() == null || input.qualifier().isBlank())) continue;
            Parameter parameter = parameter(input.parameter());
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from analysis_panel_parameter "
                + "where panel_id = ? and parameter_id = ?)", Boolean.class, sample.panelId(), parameter.id()))) {
                throw new BusinessRuleException("Parameter is not part of the selected panel: " + input.parameter());
            }
            Qualifier qualifier = qualifier(input.qualifier());
            BigDecimal value = qualifier.code().equals("NONE") ? decimal(input.value()) : null;
            BigDecimal limit = qualifier.code().equals("LESS_THAN") ? decimal(input.limit()) : null;
            if (qualifier.code().equals("LESS_THAN") && limit.signum() <= 0) {
                throw new BusinessRuleException("Quantification limit must be positive.");
            }
            List<UUID> previous = jdbc.query("select id from result where analysis_id = ? and parameter_id = ? and is_current = true for update",
                (rs, index) -> rs.getObject(1, UUID.class), sample.analysisId(), parameter.id());
            UUID previousId = previous.isEmpty() ? null : previous.getFirst();
            if (previousId != null) jdbc.update("update result set is_current = false where id = ?", previousId);
            jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, qualifier_limit, "
                    + "original_value, original_unit, equipment, supersedes_result_id, created_by_id) "
                    + "values (?, ?, ?, cast(? as result_qualifier), ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sample.analysisId(), parameter.id(), qualifier.code(), value, limit,
                input.value(), blankToNull(input.unit()), blankToNull(request.equipment()), previousId, actorId());
        }
        int completed = completed(sample.analysisId());
        int required = required(sample.panelId());
        boolean send = request.status().equals("Pendiente validar");
        if (!send && !request.status().equals("Borrador")) throw new BusinessRuleException("Unsupported analysis status.");
        int completedRequired = completedRequired(sample.analysisId(), sample.panelId());
        String status = send && completedRequired >= required ? "PENDING_VALIDATION" : completed > 0 ? "PARTIAL" : "DRAFT";
        jdbc.update("update analysis set processed_at = ?, laboratory_name = ?, equipment = ?, method_description = ?, "
                + "observations = ?, status = cast(? as analysis_status) where id = ?",
            Timestamp.from(request.processedAt().atStartOfDay(timezone).toInstant()), request.laboratory().trim(),
            blankToNull(request.equipment()), request.method().trim(), blankToNull(request.observations()),
            status, sample.analysisId());
        return get(code);
    }

    @Transactional
    public SampleResponse validate(String code, String note) {
        SampleRow sample = findForUpdate(code);
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Invalidated analysis cannot be validated.");
        int required = required(sample.panelId());
        int completedRequired = completedRequired(sample.analysisId(), sample.panelId());
        if (completedRequired < required) throw new BusinessRuleException("Mandatory parameters are missing.");
        UUID actor = actorId();
        jdbc.update("update analysis set status = 'VALIDATED'::analysis_status, validated_at = now(), "
                + "validated_by_id = ?, validation_note = ? where id = ?", actor, blankToNull(note), sample.analysisId());
        jdbc.update("update result set validated = true, validated_by_id = ?, validated_at = now() "
            + "where analysis_id = ? and is_current = true", actor, sample.analysisId());
        return get(code);
    }

    @Transactional
    public SampleResponse correct(String code, String parameterName, CorrectionRequest request) {
        SampleRow sample = findForUpdate(code);
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Invalidated analysis cannot be corrected.");
        Parameter parameter = parameter(parameterName);
        List<UUID> current = jdbc.query("select id from result where analysis_id = ? and parameter_id = ? "
                + "and is_current = true for update", (rs, index) -> rs.getObject(1, UUID.class),
            sample.analysisId(), parameter.id());
        if (current.isEmpty()) throw new NotFoundException("Current result not found.");
        UUID prior = current.getFirst();
        jdbc.update("update result set is_current = false where id = ?", prior);
        jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, original_value, "
                + "original_unit, equipment, supersedes_result_id, correction_reason, created_by_id) "
                + "select ?, analysis_id, parameter_id, 'NONE'::result_qualifier, ?, ?, original_unit, equipment, id, ?, ? "
                + "from result where id = ?", UUID.randomUUID(), decimal(request.value()), request.value(),
            request.reason().trim(), actorId(), prior);
        jdbc.update("update analysis set status = 'PENDING_VALIDATION'::analysis_status, validated_at = null, "
            + "validated_by_id = null where id = ?", sample.analysisId());
        return get(code);
    }

    @Transactional
    public SampleResponse invalidate(String code, String reason) {
        SampleRow sample = findForUpdate(code);
        jdbc.update("update analysis set status = 'INVALIDATED'::analysis_status, validation_note = ?, "
            + "validated_at = null, validated_by_id = null where id = ?", reason.trim(), sample.analysisId());
        jdbc.update("update result set validated = false, validated_at = null, validated_by_id = null "
            + "where analysis_id = ? and is_current = true", sample.analysisId());
        return get(code);
    }

    private SampleResponse response(SampleRow row) {
        String status = switch (row.status()) {
            case "PENDING_VALIDATION" -> "Pendiente validar";
            case "VALIDATED" -> "Validado";
            case "INVALIDATED" -> "Invalidado";
            case "PARTIAL", "IN_PROGRESS" -> "Análisis parcial";
            default -> "Borrador";
        };
        List<SampleResultResponse> results = jdbc.query("select r.id, p.name as parameter, r.original_value, "
                + "coalesce(r.original_unit, p.reference_unit) as unit, r.qualifier::text, r.qualifier_limit, "
                + "a.method_description from result r join parameter p on p.id = r.parameter_id "
                + "join analysis a on a.id = r.analysis_id where r.analysis_id = ? and r.is_current = true "
                + "order by p.name", (rs, index) -> result(rs, status), row.analysisId());
        List<PanelParameterResponse> panelParameters = jdbc.query("select p.name, p.reference_unit, pp.required "
                + "from analysis_panel_parameter pp join parameter p on p.id = pp.parameter_id "
                + "where pp.panel_id = ? order by p.name",
            (rs, index) -> new PanelParameterResponse(rs.getString("name"), rs.getString("reference_unit"),
                rs.getBoolean("required")), row.panelId());
        String currentDeposit = jdbc.query("select d.code from occupation o join deposit d on d.id = o.deposit_id "
                + "where o.content_unit_id = ? and o.end_at is null limit 1",
            (rs, index) -> rs.getString(1), row.contentId()).stream().findFirst().orElse(row.originDeposit());
        LocalDate takenDate = row.takenAt().atZone(timezone).toLocalDate();
        long days = ChronoUnit.DAYS.between(takenDate, LocalDate.now(timezone));
        String age = days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
        return new SampleResponse(row.code(), row.originDeposit(), currentDeposit, row.contentCode(), row.lotCode(),
            row.category() == null ? "" : row.category(), row.takenAt().atZone(timezone).toLocalDateTime().toString(),
            takenDate, age, panelName(row.panelCode()), results.size(), required(row.panelId()), status,
            row.responsible(), false, panelParameters, results, row.observations(),
            row.processedAt() == null ? null : row.processedAt().atZone(timezone).toLocalDate(),
            row.laboratory(), row.equipment(), row.method(), row.validationNote());
    }

    private SampleResultResponse result(ResultSet rs, String status) throws SQLException {
        UUID resultId = rs.getObject("id", UUID.class);
        List<ResultVersionResponse> versions = jdbc.query("with recursive history(previous_id, reason) as ("
                + "select supersedes_result_id, correction_reason from result where id = ? "
                + "union all select old.supersedes_result_id, old.correction_reason from history h "
                + "join result old on old.id = h.previous_id where old.supersedes_result_id is not null) "
                + "select old.original_value, old.created_at, u.full_name, h.reason as correction_reason "
                + "from history h join result old on old.id = h.previous_id "
                + "join app_user u on u.id = old.created_by_id order by old.created_at",
            (history, index) -> new ResultVersionResponse(history.getString("original_value"),
                history.getTimestamp("created_at").toLocalDateTime().toLocalDate().toString(),
                history.getString("full_name"), history.getString("correction_reason")), resultId);
        String qualifier = switch (rs.getString("qualifier")) {
            case "LESS_THAN" -> "Menor que límite";
            case "NOT_MEASURED" -> "No medido";
            case "NOT_DETECTED" -> "No detectado";
            default -> null;
        };
        String validity = status.equals("Validado") ? "Validado" : status.equals("Invalidado") ? "Invalidado"
            : status.equals("Pendiente validar") ? "Pendiente validar" : "Borrador";
        return new SampleResultResponse(rs.getString("parameter"), rs.getString("original_value"),
            rs.getString("unit"), validity, qualifier,
            rs.getBigDecimal("qualifier_limit") == null ? null : rs.getBigDecimal("qualifier_limit").toPlainString(),
            rs.getString("method_description"), versions);
    }

    private SampleRow find(String code, UUID centerId) {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT + " where d.center_id = ? and s.code = ?",
            (rs, index) -> sampleRow(rs), centerId, normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Sample not found.");
        return rows.getFirst();
    }

    private SampleRow findForUpdate(String code) {
        UUID centerId = context.centerId();
        List<UUID> ids = jdbc.query("select a.id from analysis a join sample s on s.id = a.sample_id "
                + "join deposit d on d.id = s.deposit_id_at_sampling where d.center_id = ? and s.code = ? "
                + "for update of a", (rs, index) -> rs.getObject(1, UUID.class), centerId, normalize(code));
        if (ids.isEmpty()) throw new NotFoundException("Sample not found.");
        return find(code, centerId);
    }

    private SampleRow sampleRow(ResultSet rs) throws SQLException {
        return new SampleRow(rs.getObject("analysis_id", UUID.class), rs.getObject("content_unit_id", UUID.class),
            rs.getObject("panel_id", UUID.class), rs.getString("code"), rs.getString("origin_deposit"),
            rs.getString("content_code"), rs.getString("lot_code"), rs.getString("category"),
            rs.getTimestamp("taken_at").toInstant(), rs.getString("panel_code"), rs.getString("responsible"),
            rs.getString("status"), rs.getString("observations"),
            rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant(),
            rs.getString("laboratory_name"), rs.getString("equipment"), rs.getString("method_description"),
            rs.getString("validation_note"));
    }

    private Parameter parameter(String value) {
        String code = PARAMETER_ALIASES.getOrDefault(value, value);
        List<Parameter> rows = jdbc.query("select id, code from parameter where lower(name) = lower(?) "
                + "or lower(code) = lower(?)", (rs, index) -> new Parameter(rs.getObject(1, UUID.class), rs.getString(2)),
            value.trim(), code.trim());
        if (rows.isEmpty()) throw new NotFoundException("Parameter not found: " + value);
        return rows.getFirst();
    }

    private UUID panelId(String value) {
        String code = switch (value) {
            case "Control" -> "CONTROL";
            case "Ampliado" -> "ROUTINE_COMPLETE";
            case "Reducido" -> "REDUCED";
            case "Maloláctica" -> "MALOLACTIC";
            default -> value;
        };
        List<UUID> ids = jdbc.query("select id from analysis_panel where code = ?",
            (rs, index) -> rs.getObject(1, UUID.class), code);
        if (ids.isEmpty()) throw new NotFoundException("Analysis panel not found.");
        return ids.getFirst();
    }

    private String panelName(String code) {
        return switch (code) {
            case "CONTROL" -> "Control";
            case "ROUTINE_COMPLETE" -> "Ampliado";
            case "REDUCED" -> "Reducido";
            case "MALOLACTIC" -> "Maloláctica";
            default -> code;
        };
    }

    private int completed(UUID analysisId) {
        return jdbc.queryForObject("select count(*) from result where analysis_id = ? and is_current = true",
            Integer.class, analysisId);
    }

    private int required(UUID panelId) {
        return jdbc.queryForObject("select count(*) from analysis_panel_parameter where panel_id = ? and required = true",
            Integer.class, panelId);
    }

    private int completedRequired(UUID analysisId, UUID panelId) {
        return jdbc.queryForObject("select count(*) from analysis_panel_parameter pp "
            + "join result r on r.parameter_id = pp.parameter_id and r.analysis_id = ? and r.is_current = true "
            + "where pp.panel_id = ? and pp.required = true", Integer.class, analysisId, panelId);
    }

    private UUID userId(String value, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from app_user where center_id = ? and active = true "
                + "and (lower(full_name) = lower(?) or lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsible user not found in the current center.");
        return ids.getFirst();
    }

    private UUID actorId() { return context.userId(); }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessRuleException("Numeric value or limit is required.");
        try { return new BigDecimal(raw.trim().replace(',', '.')); }
        catch (NumberFormatException exception) { throw new BusinessRuleException("Invalid numeric value: " + raw); }
    }

    private Qualifier qualifier(String value) {
        if (value == null || value.isBlank()) return new Qualifier("NONE");
        return switch (value) {
            case "Menor que límite" -> new Qualifier("LESS_THAN");
            case "No medido" -> new Qualifier("NOT_MEASURED");
            case "No detectado" -> new Qualifier("NOT_DETECTED");
            default -> throw new BusinessRuleException("Unsupported result qualifier.");
        };
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static final String SAMPLE_SELECT = "select a.id as analysis_id, s.content_unit_id, a.panel_id, "
        + "s.code, d.code as origin_deposit, cu.code as content_code, l.code as lot_code, "
        + "c.name as category, s.taken_at, p.code as panel_code, u.full_name as responsible, "
        + "a.status::text, s.observations, a.processed_at, a.laboratory_name, a.equipment, "
        + "a.method_description, a.validation_note from sample s "
        + "join analysis a on a.sample_id = s.id join deposit d on d.id = s.deposit_id_at_sampling "
        + "join content_unit cu on cu.id = s.content_unit_id join lot l on l.id = cu.lot_id "
        + "left join internal_category c on c.id = cu.category_id "
        + "join analysis_panel p on p.id = a.panel_id join app_user u on u.id = s.taken_by_id";

    private record OccupationAtTime(UUID occupationId, UUID contentId, String contentCode, String lotCode) {}
    private record Parameter(UUID id, String code) {}
    private record Qualifier(String code) {}
    private record SampleRow(UUID analysisId, UUID contentId, UUID panelId, String code, String originDeposit,
                             String contentCode, String lotCode, String category, Instant takenAt,
                             String panelCode, String responsible, String status, String observations,
                             Instant processedAt, String laboratory, String equipment, String method,
                             String validationNote) {}
}
