package coop.miriv.enology.laboratory.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.CategoryRef;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelCategory;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelCreateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelParameter;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelUpdateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelView;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterRef;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterView;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The parameter catalogue and the analysis templates (panels) built from it, plus which content
 * categories each template serves. Codes are immutable once created — results and rules refer to them —
 * but everything else can be edited; nothing is deleted, only deactivated, so history keeps its meaning.
 */
@Service
public class PanelAdminService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PanelAdminService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ parameters

    @Transactional(readOnly = true)
    public List<ParameterView> parameters() {
        return jdbc.query("""
            select p.code, p.name, p.reference_unit, p.decimal_places, p.plausibility_min, p.plausibility_max,
                   p.description, p.active,
                   (select count(*) from analysis_panel_parameter pp where pp.parameter_id = p.id) panels
              from parameter p order by p.name""",
            (rs, index) -> new ParameterView(rs.getString("code"), rs.getString("name"), rs.getString("reference_unit"),
                rs.getInt("decimal_places"), rs.getBigDecimal("plausibility_min"), rs.getBigDecimal("plausibility_max"),
                rs.getString("description"), rs.getBoolean("active"), rs.getInt("panels")));
    }

    @Transactional
    public ParameterView createParameter(ParameterRequest request) {
        if (request.code() == null) throw new BusinessRuleException("Indica el código del parámetro.");
        checkRange(request);
        if (exists("parameter", request.code())) {
            throw new ConflictException("DUPLICATE_CODE", "Ya existe un parámetro con el código " + request.code() + ".");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into parameter(id, code, name, reference_unit, decimal_places, plausibility_min, plausibility_max, "
                + "description, active) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, request.code(), request.name().trim(), request.unit().trim(), request.decimals(),
            request.plausibilityMin(), request.plausibilityMax(), blankToNull(request.description()),
            request.active() == null || request.active());
        audit.record("parameter", id, "PARAMETER_CREATED", request.code() + " · " + request.name().trim());
        return parameter(request.code());
    }

    @Transactional
    public ParameterView updateParameter(String code, ParameterRequest request) {
        checkRange(request);
        UUID id = idOf("parameter", code, "Parámetro no encontrado.");
        jdbc.update("update parameter set name = ?, reference_unit = ?, decimal_places = ?, plausibility_min = ?, "
                + "plausibility_max = ?, description = ?, active = coalesce(?, active) where id = ?",
            request.name().trim(), request.unit().trim(), request.decimals(), request.plausibilityMin(),
            request.plausibilityMax(), blankToNull(request.description()), request.active(), id);
        audit.record("parameter", id, "PARAMETER_UPDATED", code + " · " + request.name().trim());
        return parameter(code);
    }

    private ParameterView parameter(String code) {
        return parameters().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private static void checkRange(ParameterRequest request) {
        if (request.plausibilityMin() != null && request.plausibilityMax() != null
                && request.plausibilityMin().compareTo(request.plausibilityMax()) > 0) {
            throw new BusinessRuleException("El mínimo plausible no puede ser mayor que el máximo.");
        }
    }

    // ------------------------------------------------------------------ panels

    @Transactional(readOnly = true)
    public List<PanelView> panels() {
        return jdbc.query("""
            select p.id, p.code, p.name, p.description, p.active,
                   (select count(*) from analysis a where a.panel_id = p.id) samples
              from analysis_panel p order by p.active desc, p.name""",
            (rs, index) -> {
                UUID id = rs.getObject("id", UUID.class);
                return new PanelView(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                    rs.getBoolean("active"), panelParameters(id), panelCategories(id), rs.getInt("samples"));
            });
    }

    /** Active templates a category offers, the default first: what the sample form proposes. */
    @Transactional(readOnly = true)
    public List<PanelView> panelsForCategory(String categoryCode) {
        List<PanelView> all = panels().stream().filter(PanelView::active).toList();
        if (categoryCode == null || categoryCode.isBlank()) return all;
        String wanted = categoryCode.trim();
        List<PanelView> assigned = all.stream()
            .filter(panel -> panel.categories().stream().anyMatch(c -> matches(c, wanted)))
            .sorted((a, b) -> Boolean.compare(isDefault(b, wanted), isDefault(a, wanted)))
            .toList();
        // A category with nothing assigned still gets every template rather than an empty form.
        return assigned.isEmpty() ? all : assigned;
    }

    @Transactional
    public PanelView createPanel(PanelCreateRequest request) {
        if (request.code() == null) throw new BusinessRuleException("Indica el código de la plantilla.");
        if (exists("analysis_panel", request.code())) {
            throw new ConflictException("DUPLICATE_CODE", "Ya existe una plantilla con el código " + request.code() + ".");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into analysis_panel(id, code, name, description) values (?, ?, ?, ?)",
            id, request.code(), request.name().trim(), blankToNull(request.description()));
        audit.record("analysis_panel", id, "PANEL_CREATED", request.code() + " · " + request.name().trim());
        return panel(request.code());
    }

    @Transactional
    public PanelView updatePanel(String code, PanelUpdateRequest request) {
        UUID panelId = idOf("analysis_panel", code, "Plantilla no encontrada.");

        Set<String> seen = new HashSet<>();
        for (ParameterRef ref : request.parameters()) {
            if (!seen.add(ref.code())) throw new BusinessRuleException("El parámetro " + ref.code() + " está repetido.");
        }
        if (request.active() && request.parameters().isEmpty()) {
            throw new BusinessRuleException("Una plantilla activa necesita al menos un parámetro.");
        }
        Set<String> categories = new HashSet<>();
        for (CategoryRef ref : request.categories()) {
            if (!categories.add(ref.code())) throw new BusinessRuleException("La categoría " + ref.code() + " está repetida.");
        }

        jdbc.update("update analysis_panel set name = ?, description = ?, active = ? where id = ?",
            request.name().trim(), blankToNull(request.description()), request.active(), panelId);

        // Parameters: replace the list, keeping the order it was given in.
        jdbc.update("delete from analysis_panel_parameter where panel_id = ?", panelId);
        int position = 1;
        for (ParameterRef ref : request.parameters()) {
            UUID parameterId = idOf("parameter", ref.code(), "Parámetro no encontrado: " + ref.code());
            jdbc.update("insert into analysis_panel_parameter(panel_id, parameter_id, required, position) values (?, ?, ?, ?)",
                panelId, parameterId, ref.required(), position++);
        }

        // Categories: replace the assignments; a new default takes over from the category's previous one.
        jdbc.update("delete from analysis_panel_category where panel_id = ?", panelId);
        for (CategoryRef ref : request.categories()) {
            UUID categoryId = categoryId(ref.code());
            if (ref.isDefault()) {
                jdbc.update("update analysis_panel_category set is_default = false where category_id = ? and is_default",
                    categoryId);
            }
            jdbc.update("insert into analysis_panel_category(panel_id, category_id, is_default) values (?, ?, ?)",
                panelId, categoryId, ref.isDefault());
        }

        audit.record("analysis_panel", panelId, "PANEL_UPDATED",
            code + " · " + request.parameters().size() + " parámetros · " + request.categories().size() + " categorías",
            null, Map.of("parameters", request.parameters().stream().map(ParameterRef::code).toList(),
                "categories", request.categories().stream().map(CategoryRef::code).toList()));
        return panel(code);
    }

    private PanelView panel(String code) {
        return panels().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private List<PanelParameter> panelParameters(UUID panelId) {
        return jdbc.query("select par.code, par.name, par.reference_unit, pp.required from analysis_panel_parameter pp "
                + "join parameter par on par.id = pp.parameter_id where pp.panel_id = ? order by pp.position, par.name",
            (rs, index) -> new PanelParameter(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), panelId);
    }

    private List<PanelCategory> panelCategories(UUID panelId) {
        return jdbc.query("select c.code, c.name, pc.is_default from analysis_panel_category pc "
                + "join internal_category c on c.id = pc.category_id where pc.panel_id = ? order by c.name",
            (rs, index) -> new PanelCategory(rs.getString(1), rs.getString(2), rs.getBoolean(3)), panelId);
    }

    private static boolean matches(PanelCategory category, String wanted) {
        return category.code().equalsIgnoreCase(wanted) || category.name().equalsIgnoreCase(wanted);
    }

    private static boolean isDefault(PanelView panel, String category) {
        return panel.categories().stream().anyMatch(c -> matches(c, category) && c.isDefault());
    }

    // ------------------------------------------------------------------ helpers

    private UUID categoryId(String codeOrName) {
        return jdbc.query("select id from internal_category where lower(code) = lower(?) or lower(name) = lower(?)",
            (rs, index) -> rs.getObject(1, UUID.class), codeOrName, codeOrName).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Categoría no encontrada: " + codeOrName));
    }

    private boolean exists(String table, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from " + table + " where upper(code) = ?)", Boolean.class, code.toUpperCase(Locale.ROOT)));
    }

    private UUID idOf(String table, String code, String notFound) {
        // `table` only ever receives the two fixed internal names above.
        return jdbc.query("select id from " + table + " where upper(code) = ?", (rs, index) -> rs.getObject(1, UUID.class),
            code.trim().toUpperCase(Locale.ROOT)).stream().findFirst().orElseThrow(() -> new NotFoundException(notFound));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
