package coop.miriv.enology.laboratory.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.SuperAdminCheck;
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
    private final AuditService audit;
    private final SuperAdminCheck superAdmin;
    private final ZoneId timezone;

    public LaboratoryService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit,
                             SuperAdminCheck superAdmin, @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.superAdmin = superAdmin;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public List<SampleResponse> list() {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT + " where d.center_id = ?" + context.readZoneFilter("d").sql() + " order by s.taken_at desc",
            (rs, index) -> sampleRow(rs), prependZoneFilter(context.centerId(), context.readZoneFilter("d")));
        return rows.stream().map(this::response).toList();
    }

    /** F2-07: avoid the N+1 in {@code ContentService.get} by filtering samples at SQL level. */
    @Transactional(readOnly = true)
    public List<SampleResponse> listByContent(String contentCode) {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT
                + " where d.center_id = ? and cu.code = ?" + context.readZoneFilter("d").sql() + " order by s.taken_at desc",
            (rs, index) -> sampleRow(rs),
            prependZoneFilter(context.centerId(), context.readZoneFilter("d"), normalize(contentCode)));
        return rows.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SampleResponse get(String code) { return response(find(code, context.centerId())); }

    @Transactional
    public SampleResponse create(NewSampleRequest request) {
        UUID centerId = context.centerId();
        String code = normalize(request.code());
        // F2-04 idempotency: a sample with the same code, deposit, content and takenAt is a
        // legitimate retry (e.g. network blip during the lab assistant submit). Return the
        // existing sample instead of erroring out; only treat it as a real duplicate when
        // the other identifying fields differ.
        SampleResponse existing = findByNaturalKey(code, centerId, request);
        if (existing != null) return existing;
        if (!request.takenDate().equals(request.takenAt().toLocalDate())) {
            throw new BusinessRuleException("La fecha y la hora del muestreo no coinciden.");
        }
        Instant takenAt = request.takenAt().atZone(timezone).toInstant();
        if (takenAt.isAfter(Instant.now())) throw new BusinessRuleException("La hora del muestreo no puede estar en el futuro.");
        List<OccupationAtTime> occupations = jdbc.query(
            "select o.id, o.content_unit_id, cu.code as content_code, l.code as lot_code "
                + "from occupation o join deposit d on d.id = o.deposit_id "
                + "join content_unit cu on cu.id = o.content_unit_id join lot l on l.id = cu.lot_id "
                + "where d.center_id = ? and d.code = ? and o.start_at <= ? and (o.end_at is null or o.end_at > ?) "
                + "order by o.start_at desc limit 1",
            (rs, index) -> new OccupationAtTime(rs.getObject("id", UUID.class),
                rs.getObject("content_unit_id", UUID.class), rs.getString("content_code"), rs.getString("lot_code")),
            centerId, normalize(request.originDeposit()), Timestamp.from(takenAt), Timestamp.from(takenAt));
        if (occupations.isEmpty()) throw new BusinessRuleException("Ningún contenido ocupaba el depósito en el momento del muestreo.");
        OccupationAtTime occupation = occupations.getFirst();
        if (!occupation.contentCode().equalsIgnoreCase(request.contentCode())) {
            throw new BusinessRuleException("El contenido de la muestra no coincide con la ocupación histórica del depósito.");
        }
        if (request.lotCode() != null && !request.lotCode().isBlank()
            && !occupation.lotCode().equalsIgnoreCase(request.lotCode())) {
            throw new BusinessRuleException("El lote de la muestra no coincide con la ocupación histórica del depósito.");
        }
        UUID depositId = jdbc.queryForObject("select id from deposit where center_id = ? and code = ?", UUID.class,
            centerId, normalize(request.originDeposit()));
        UUID originZoneId = jdbc.queryForObject("select zone_id from deposit where id = ?", UUID.class, depositId);
        context.requireInZone("SAMPLE_REGISTER", originZoneId);
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
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Un análisis invalidado no se puede editar.");
        if (sample.status().equals("VALIDATED")) throw new BusinessRuleException("Para cambiar un resultado validado usa la corrección.");
        for (ResultInput input : request.results()) {
            if ((input.value() == null || input.value().isBlank())
                && (input.qualifier() == null || input.qualifier().isBlank())) continue;
            Parameter parameter = parameter(input.parameter(), sample.panelId());
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from analysis_panel_parameter "
                + "where panel_id = ? and parameter_id = ?)", Boolean.class, sample.panelId(), parameter.id()))) {
                throw new BusinessRuleException("El parámetro no forma parte del panel seleccionado: " + input.parameter());
            }
            Qualifier qualifier = qualifier(input.qualifier());
            BigDecimal value = qualifier.code().equals("NONE") ? decimal(input.value()) : null;
            BigDecimal limit = qualifier.code().equals("LESS_THAN") ? decimal(input.limit()) : null;
            if (qualifier.code().equals("LESS_THAN") && limit.signum() <= 0) {
                throw new BusinessRuleException("El límite de cuantificación debe ser positivo.");
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
        if (!send && !request.status().equals("Borrador")) throw new BusinessRuleException("Estado de análisis no soportado.");
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
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Un análisis invalidado no se puede validar.");
        if (sample.status().equals("DRAFT") || sample.status().equals("PARTIAL")) throw new BusinessRuleException("Análisis en borrador: complétalo antes de validarlo.");
        int required = required(sample.panelId());
        int completedRequired = completedRequired(sample.analysisId(), sample.panelId());
        if (completedRequired < required) {
            throw new BusinessRuleException("MISSING_REQUIRED_PARAMETERS",
                "Faltan parámetros obligatorios: " + (required - completedRequired) + " pendiente(s).");
        }
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
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Un análisis invalidado no se puede corregir.");
        Parameter parameter = parameter(parameterName, sample.panelId());
        List<UUID> current = jdbc.query("select id from result where analysis_id = ? and parameter_id = ? "
                + "and is_current = true for update", (rs, index) -> rs.getObject(1, UUID.class),
            sample.analysisId(), parameter.id());
        if (current.isEmpty()) throw new NotFoundException("Resultado actual no encontrado.");
        UUID prior = current.getFirst();
        jdbc.update("update result set is_current = false where id = ?", prior);
        jdbc.update("insert into result(id, analysis_id, parameter_id, qualifier, numeric_value, qualifier_limit, "
                + "original_value, original_unit, equipment, supersedes_result_id, correction_reason, created_by_id) "
                + "select ?, analysis_id, parameter_id, qualifier, ?, qualifier_limit, ?, original_unit, equipment, id, ?, ? "
                + "from result where id = ?", UUID.randomUUID(), decimal(request.value()), request.value(),
            request.reason().trim(), actorId(), prior);
        jdbc.update("update analysis set status = 'PENDING_VALIDATION'::analysis_status, validated_at = null, "
            + "validated_by_id = null where id = ?", sample.analysisId());
        return get(code);
    }

    /**
     * Retires an analysis keeping its evidence. A validated one can be invalidated too: a reading taken
     * from the wrong tank or with a broken probe has to be retired as a whole, not corrected value by value.
     */
    @Transactional
    public SampleResponse invalidate(String code, String reason) {
        SampleRow sample = findForUpdate(code);
        if (sample.status().equals("INVALIDATED")) throw new BusinessRuleException("Análisis ya invalidado.");
        boolean wasValidated = sample.status().equals("VALIDATED");
        jdbc.update("update analysis set status = 'INVALIDATED'::analysis_status, validation_note = ?, "
            + "validated_at = null, validated_by_id = null where id = ?", reason.trim(), sample.analysisId());
        jdbc.update("update result set validated = false, validated_at = null, validated_by_id = null "
            + "where analysis_id = ? and is_current = true", sample.analysisId());
        audit.record("analysis", sample.analysisId(), "ANALYSIS_INVALIDATED",
            (wasValidated ? "Análisis validado invalidado: " : "Análisis invalidado: ") + reason.trim());
        return get(code);
    }

    /**
     * Removes a sample and everything hanging from it for good: only a super administrator, only with a
     * reason, and only after the audit entry keeping what was deleted. Invalidating is the reversible
     * option; this one is for samples that should never have existed (a duplicated import, a test row).
     */
    @Transactional
    public void deleteSample(String code, String reason) {
        superAdmin.require("eliminar definitivamente un análisis");
        SampleRow sample = findForUpdate(code);
        UUID sampleId = jdbc.queryForObject("select id from sample where code = ?", UUID.class, sample.code());

        audit.record("sample", sampleId, "SAMPLE_DELETED", reason.trim(),
            Map.of("code", sample.code(), "deposit", sample.originDeposit(), "content", sample.contentCode(),
                "takenAt", sample.takenAt().toString(), "status", sample.status(),
                "results", jdbc.queryForObject("select count(*) from result where analysis_id = ?",
                    Integer.class, sample.analysisId())),
            null);

        // Evidence and executions point at the sample/results: they keep their note, they lose the link.
        jdbc.update("update incident_evidence set result_id = null where result_id in "
            + "(select id from result where analysis_id = ?)", sample.analysisId());
        jdbc.update("update incident_evidence set sample_id = null where sample_id = ?", sampleId);
        jdbc.update("update task_execution set sample_id = null where sample_id = ?", sampleId);
        // Corrections chain results to each other; unlink before deleting so no row keeps a dangling parent.
        jdbc.update("update result set supersedes_result_id = null where analysis_id = ?", sample.analysisId());
        jdbc.update("delete from result where analysis_id = ?", sample.analysisId());
        jdbc.update("delete from analysis where sample_id = ?", sampleId);
        jdbc.update("delete from sample where id = ?", sampleId);
    }

    /**
     * Moves a sample to the deposit it was really taken from, when it was registered against the wrong tank.
     * The content and occupation are resolved by the sampling time, exactly as in {@link #create}, so the
     * results follow the wine that actually occupied that deposit then; curves, alerts and history move with it.
     * Requires SAMPLE_REASSIGN in the zones of both the current and the new deposit, and keeps the reason in the audit log.
     */
    @Transactional
    public SampleResponse reassignDeposit(String code, String depositCode, String reason) {
        UUID centerId = context.centerId();
        SampleRow sample = findForUpdate(code);
        String target = normalize(depositCode);

        UUID currentZoneId = jdbc.queryForObject("select d.zone_id from sample s "
            + "join deposit d on d.id = s.deposit_id_at_sampling where s.code = ?", UUID.class, sample.code());
        context.requireInZone("SAMPLE_REASSIGN", currentZoneId);

        List<Deposit> targets = jdbc.query("select id, zone_id from deposit where center_id = ? and upper(code) = ? and active",
            (rs, index) -> new Deposit(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class)), centerId, target);
        if (targets.isEmpty()) throw new NotFoundException("Depósito no encontrado.");
        Deposit deposit = targets.getFirst();
        context.requireInZone("SAMPLE_REASSIGN", deposit.zoneId());

        if (target.equalsIgnoreCase(normalize(sample.originDeposit()))) {
            throw new BusinessRuleException("La muestra ya está asignada a ese depósito.");
        }

        List<OccupationAtTime> occupations = jdbc.query(
            "select o.id, o.content_unit_id, cu.code as content_code, l.code as lot_code "
                + "from occupation o join content_unit cu on cu.id = o.content_unit_id join lot l on l.id = cu.lot_id "
                + "where o.deposit_id = ? and o.start_at <= ? and (o.end_at is null or o.end_at > ?) "
                + "order by o.start_at desc limit 1",
            (rs, index) -> new OccupationAtTime(rs.getObject("id", UUID.class),
                rs.getObject("content_unit_id", UUID.class), rs.getString("content_code"), rs.getString("lot_code")),
            deposit.id(), Timestamp.from(sample.takenAt()), Timestamp.from(sample.takenAt()));
        if (occupations.isEmpty()) {
            throw new BusinessRuleException("Ningún contenido ocupaba ese depósito en el momento del muestreo.");
        }
        OccupationAtTime occupation = occupations.getFirst();

        jdbc.update("update sample set content_unit_id = ?, occupation_id = ?, deposit_id_at_sampling = ? where code = ?",
            occupation.contentId(), occupation.occupationId(), deposit.id(), sample.code());
        audit.record("sample", sample.analysisId(), "SAMPLE_REASSIGNED", reason.trim(),
            Map.of("deposit", sample.originDeposit(), "content", sample.contentCode()),
            Map.of("deposit", target, "content", occupation.contentCode()));
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
        // F2-03: age is computed client-side via formatRelative(takenAt) instead of a server-side string.
        return new SampleResponse(row.code(), row.originDeposit(), currentDeposit, row.contentCode(), row.lotCode(),
            row.category(), row.takenAt().atZone(timezone).toLocalDateTime().toString(),
            takenDate, null, panelName(row.panelCode()), completedRequired(row.analysisId(), row.panelId()), required(row.panelId()), status,
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

    /**
     * F2-04: if a sample with the same code, deposit, content and takenAt already exists,
     * return it instead of creating a duplicate. A truly different sample carrying the
     * same code is still surfaced as a {@code DUPLICATE_CODE} conflict.
     *
     * Deliberately NOT scoped by {@link CurrentUserContext#readZoneFilter}: {@code sample.code}
     * is unique for the whole center regardless of zone, so this must see every zone or a
     * duplicate the caller cannot read would fall through to a generic constraint-violation 409
     * on insert instead of the specific {@code DUPLICATE_CODE} conflict.
     */
    private SampleResponse findByNaturalKey(String code, UUID centerId, NewSampleRequest request) {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT + " where d.center_id = ? and s.code = ?",
            (rs, index) -> sampleRow(rs), centerId, code);
        if (rows.isEmpty()) return null;
        SampleRow existing = rows.getFirst();
        boolean sameDeposit = existing.originDeposit().equalsIgnoreCase(normalize(request.originDeposit()));
        boolean sameContent = existing.contentCode().equalsIgnoreCase(request.contentCode());
        boolean sameTakenAt = existing.takenAt().equals(request.takenAt().atZone(timezone).toInstant());
        if (sameDeposit && sameContent && sameTakenAt) return response(existing);
        throw new ConflictException("DUPLICATE_CODE", "Ya existe una muestra con ese código.");
    }

    private SampleRow find(String code, UUID centerId) {
        List<SampleRow> rows = jdbc.query(SAMPLE_SELECT + " where d.center_id = ? and s.code = ?" + context.readZoneFilter("d").sql(),
            (rs, index) -> sampleRow(rs), prependZoneFilter(centerId, context.readZoneFilter("d"), normalize(code)));
        if (rows.isEmpty()) throw new NotFoundException("Muestra no encontrada.");
        return rows.getFirst();
    }

    private SampleRow findForUpdate(String code) {
        UUID centerId = context.centerId();
        List<UUID> ids = jdbc.query("select a.id from analysis a join sample s on s.id = a.sample_id "
                + "join deposit d on d.id = s.deposit_id_at_sampling where d.center_id = ? and s.code = ? "
                + "for update of a", (rs, index) -> rs.getObject(1, UUID.class), centerId, normalize(code));
        if (ids.isEmpty()) throw new NotFoundException("Muestra no encontrada.");
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

    /**
     * Resolves a parameter by name, code or legacy alias. A label can match more than one parameter
     * (e.g. "Ácido málico" is MALIC_ACID by name and L_MALIC_ACID by alias), so the one in the
     * sample's panel wins, then an exact name match, then the alias.
     */
    private Parameter parameter(String value, UUID panelId) {
        String code = PARAMETER_ALIASES.getOrDefault(value, value);
        List<Parameter> rows = jdbc.query("select p.id, p.code from parameter p where lower(p.name) = lower(?) "
                + "or lower(p.code) = lower(?) "
                + "order by exists(select 1 from analysis_panel_parameter app where app.panel_id = ? and app.parameter_id = p.id) desc, "
                + "(lower(p.name) = lower(?)) desc",
            (rs, index) -> new Parameter(rs.getObject(1, UUID.class), rs.getString(2)),
            value.trim(), code.trim(), panelId, value.trim());
        if (rows.isEmpty()) throw new NotFoundException("Parámetro no encontrado: " + value);
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
        if (ids.isEmpty()) throw new NotFoundException("Panel de análisis no encontrado.");
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
                + "and (lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsable no encontrado en el centro actual: " + value);
        return ids.getFirst();
    }

    private UUID actorId() { return context.userId(); }

    /**
     * Builds the full parameter array for a query shaped as
     * {@code "... where d.center_id = ? and <other ordinary conditions with their own ?> "
     * + context.readZoneFilter("d").sql()}: {@code centerId}, then {@code middleParams} in the
     * same order as their {@code ?} placeholders appear in the SQL text, then the zone ids last
     * — because {@code readZoneFilter(...).sql()} is always appended at the very end of the
     * WHERE clause, so its own {@code ?} placeholders come after every other one.
     *
     * Passing the zone ids and the trailing params as two separate varargs to
     * {@code jdbc.query(...)} (instead of through one merged array) makes the JDBC driver try to
     * bind the whole {@code Object[]} as a single SQL parameter and fail with "Cannot cast an
     * instance of [Ljava.lang.Object; to type Types.ARRAY" — always merge here, in this order.
     */
    private static Object[] prependZoneFilter(UUID centerId, CurrentUserContext.ZoneFilter filter, Object... middleParams) {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(centerId);
        params.addAll(java.util.Arrays.asList(middleParams));
        params.addAll(filter.zoneIds());
        return params.toArray();
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessRuleException("Es obligatorio un valor numérico o un límite.");
        try { return new BigDecimal(raw.trim().replace(',', '.')); }
        catch (NumberFormatException exception) { throw new BusinessRuleException("Valor numérico no válido: " + raw); }
    }

    private Qualifier qualifier(String value) {
        if (value == null || value.isBlank()) return new Qualifier("NONE");
        return switch (value) {
            case "Menor que límite" -> new Qualifier("LESS_THAN");
            case "No medido" -> new Qualifier("NOT_MEASURED");
            case "No detectado" -> new Qualifier("NOT_DETECTED");
            default -> throw new BusinessRuleException("Calificador de resultado no soportado.");
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

    private record Deposit(UUID id, UUID zoneId) {}
    private record OccupationAtTime(UUID occupationId, UUID contentId, String contentCode, String lotCode) {}
    private record Parameter(UUID id, String code) {}
    private record Qualifier(String code) {}
    private record SampleRow(UUID analysisId, UUID contentId, UUID panelId, String code, String originDeposit,
                             String contentCode, String lotCode, String category, Instant takenAt,
                             String panelCode, String responsible, String status, String observations,
                             Instant processedAt, String laboratory, String equipment, String method,
                             String validationNote) {}
}
