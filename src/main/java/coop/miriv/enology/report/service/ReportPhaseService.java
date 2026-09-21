package coop.miriv.enology.report.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.cellar.service.FermentationPhase;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.report.dto.ReportDto.PhaseRequest;
import coop.miriv.enology.report.dto.ReportDto.PhaseView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Report phases (V51): which phase a content is in, derived from its category and fermentation states,
 * and which parameters the report charts for that phase. Editable from Administración › Analítica.
 */
@Service
public class ReportPhaseService {

    /** Pseudo-state for "no state recorded for this process". */
    public static final String NO_STATE = "NONE";

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    /** A phase ready to match contents against. */
    public record Phase(UUID id, String code, String name, String description, String color, int position,
                        boolean active, List<String> categories, List<String> alcoholic, List<String> malolactic,
                        List<String> parameters) {

        public boolean matches(String categoryCode, String alcoholicState, String malolacticState) {
            return (categories.isEmpty() || (categoryCode != null && contains(categories, categoryCode)))
                && (alcoholic.isEmpty() || contains(alcoholic, stateOrNone(alcoholicState)))
                && (malolactic.isEmpty() || contains(malolactic, stateOrNone(malolacticState)));
        }

        private static boolean contains(List<String> values, String value) {
            return values.stream().anyMatch(item -> item.equalsIgnoreCase(value));
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;

    public ReportPhaseService(JdbcTemplate jdbc, AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    /** First active phase (by position) whose criteria match; null when none does. */
    public static Phase resolve(List<Phase> phases, String categoryCode, String alcoholicState, String malolacticState) {
        for (Phase phase : phases) {
            if (phase.active() && phase.matches(categoryCode, alcoholicState, malolacticState)) return phase;
        }
        return null;
    }

    /** A fermentation state stored as NOT_EVALUATED / NOT_EVALUABLE says nothing: it counts as no state. */
    static String stateOrNone(String state) {
        if (state == null || state.isBlank()) return NO_STATE;
        String code = FermentationPhase.code(state);
        return code == null ? NO_STATE : code;
    }

    @Transactional(readOnly = true)
    public List<Phase> all() {
        return jdbc.query("select * from report_phase order by position, name", (rs, n) -> phase(rs));
    }

    @Transactional(readOnly = true)
    public List<PhaseView> list() {
        return all().stream().map(ReportPhaseService::view).toList();
    }

    @Transactional
    public PhaseView create(PhaseRequest request) {
        Clean clean = validate(request);
        String code = request.code() == null || request.code().isBlank() ? codeFrom(request.name()) : normalizeCode(request.code());
        if (code.isEmpty()) throw new BusinessRuleException("Indica un código para la fase.");
        requireUniqueCode(code, null);
        Integer position = jdbc.queryForObject("select coalesce(max(position), 0) + 1 from report_phase", Integer.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into report_phase(id, code, name, description, color, position, active, category_codes, "
                + "alcoholic_states, malolactic_states, parameter_codes) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), "
                + "cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))",
            id, code, request.name().trim(), blankToNull(request.description()), clean.color(), position,
            request.active() == null || request.active(), json.writeValueAsString(clean.categories()),
            json.writeValueAsString(clean.alcoholic()), json.writeValueAsString(clean.malolactic()),
            json.writeValueAsString(clean.parameters()));
        audit.record("report_phase", id, "REPORT_PHASE_SAVED", "Fase de informe creada: " + request.name().trim());
        return get(id);
    }

    @Transactional
    public PhaseView update(UUID id, PhaseRequest request) {
        get(id);
        Clean clean = validate(request);
        String code = request.code() == null || request.code().isBlank() ? null : normalizeCode(request.code());
        if (code != null) requireUniqueCode(code, id);
        jdbc.update("update report_phase set code = coalesce(?, code), name = ?, description = ?, color = ?, active = ?, "
                + "category_codes = cast(? as jsonb), alcoholic_states = cast(? as jsonb), malolactic_states = cast(? as jsonb), "
                + "parameter_codes = cast(? as jsonb), updated_at = now() where id = ?",
            code, request.name().trim(), blankToNull(request.description()), clean.color(),
            request.active() == null || request.active(), json.writeValueAsString(clean.categories()),
            json.writeValueAsString(clean.alcoholic()), json.writeValueAsString(clean.malolactic()),
            json.writeValueAsString(clean.parameters()), id);
        audit.record("report_phase", id, "REPORT_PHASE_SAVED", "Fase de informe modificada: " + request.name().trim());
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        PhaseView phase = get(id);
        jdbc.update("delete from report_phase where id = ?", id);
        audit.record("report_phase", id, "REPORT_PHASE_DELETED", "Fase de informe eliminada: " + phase.name());
    }

    /** Stores the order of evaluation: the first matching phase wins, so order matters. */
    @Transactional
    public List<PhaseView> reorder(List<UUID> ids) {
        Set<UUID> known = new HashSet<>(jdbc.queryForList("select id from report_phase", UUID.class));
        if (ids.size() != known.size() || !known.equals(new HashSet<>(ids))) {
            throw new BusinessRuleException("El nuevo orden debe incluir todas las fases una sola vez.");
        }
        for (int index = 0; index < ids.size(); index++) {
            jdbc.update("update report_phase set position = ?, updated_at = now() where id = ?", index + 1, ids.get(index));
        }
        audit.record("report_phase", ids.getFirst(), "REPORT_PHASE_SAVED", "Orden de fases de informe cambiado");
        return list();
    }

    private PhaseView get(UUID id) {
        return jdbc.query("select * from report_phase where id = ?", (rs, n) -> view(phase(rs)), id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Fase de informe no encontrada."));
    }

    private record Clean(String color, List<String> categories, List<String> alcoholic, List<String> malolactic,
                         List<String> parameters) {}

    private Clean validate(PhaseRequest request) {
        String color = request.color() == null || request.color().isBlank() ? "#6d4656" : request.color().trim();
        if (!COLOR.matcher(color).matches()) throw new BusinessRuleException("El color debe tener el formato #RRGGBB.");
        List<String> categories = upper(request.categoryCodes());
        if (!categories.isEmpty()) {
            Set<String> existing = new HashSet<>(jdbc.queryForList("select upper(code) from internal_category", String.class));
            List<String> unknown = categories.stream().filter(code -> !existing.contains(code)).toList();
            if (!unknown.isEmpty()) throw new BusinessRuleException("Categorías desconocidas: " + String.join(", ", unknown));
        }
        List<String> alcoholic = states(request.alcoholicStates(), FermentationPhase.ALCOHOLIC, "alcohólica");
        List<String> malolactic = states(request.malolacticStates(), FermentationPhase.MALOLACTIC, "maloláctica");
        List<String> parameters = upper(request.parameterCodes());
        if (parameters.isEmpty()) throw new BusinessRuleException("Elige al menos un parámetro para graficar en esta fase.");
        if (parameters.size() > 16) throw new BusinessRuleException("Máximo 16 parámetros por fase.");
        Set<String> known = new HashSet<>(jdbc.queryForList("select upper(code) from parameter", String.class));
        List<String> unknown = parameters.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) throw new BusinessRuleException("Parámetros desconocidos: " + String.join(", ", unknown));
        return new Clean(color, categories, alcoholic, malolactic, parameters);
    }

    private static List<String> states(List<String> values, Set<String> allowed, String process) {
        List<String> out = new ArrayList<>();
        for (String value : upper(values)) {
            String code = NO_STATE.equals(value) ? NO_STATE : FermentationPhase.code(value);
            if (code == null || (!NO_STATE.equals(code) && !allowed.contains(code))) {
                throw new BusinessRuleException("Estado de fermentación " + process + " no válido: " + value);
            }
            if (!out.contains(code)) out.add(code);
        }
        return out;
    }

    private void requireUniqueCode(String code, UUID excludeId) {
        Integer same = jdbc.queryForObject("select count(*) from report_phase where upper(code) = ? and (?::uuid is null or id <> ?::uuid)",
            Integer.class, code, excludeId, excludeId);
        if (same != null && same > 0) throw new ConflictException("DUPLICATE_REPORT_PHASE", "Ya existe una fase con el código " + code + ".");
    }

    private Phase phase(ResultSet rs) throws SQLException {
        return new Phase(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getString("color"), rs.getInt("position"), rs.getBoolean("active"),
            json.readValue(rs.getString("category_codes"), STRINGS), json.readValue(rs.getString("alcoholic_states"), STRINGS),
            json.readValue(rs.getString("malolactic_states"), STRINGS), json.readValue(rs.getString("parameter_codes"), STRINGS));
    }

    private static PhaseView view(Phase phase) {
        return new PhaseView(phase.id(), phase.code(), phase.name(), phase.description(), phase.color(), phase.position(),
            phase.active(), phase.categories(), phase.alcoholic(), phase.malolactic(), phase.parameters());
    }

    private static List<String> upper(List<String> values) {
        if (values == null) return List.of();
        return new ArrayList<>(values.stream().filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String codeFrom(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String code = normalizeCode(ascii);
        return code.length() > 40 ? code.substring(0, 40) : code;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
