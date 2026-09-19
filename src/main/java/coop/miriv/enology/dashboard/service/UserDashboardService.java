package coop.miriv.enology.dashboard.service;

import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardRequest;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardSummary;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardView;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Personal dashboards of the tracking section: every query is scoped to the current user. */
@Service
public class UserDashboardService {

    static final int MAX_WIDGETS = 30;
    static final int MAX_JSON_CHARS = 200_000;
    private static final String DEFAULT_NAME = "Mi seguimiento";

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final JsonMapper json;

    public UserDashboardService(JdbcTemplate jdbc, CurrentUserContext context, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.json = json;
    }

    /** Lists the user's dashboards; the first time, creates the default (empty) one the frontend fills with its template. */
    @Transactional
    public List<DashboardSummary> list() {
        UUID user = context.userId();
        Integer count = jdbc.queryForObject("select count(*) from user_dashboard where user_id = ?", Integer.class, user);
        if (count == null || count == 0) {
            jdbc.update("insert into user_dashboard(id, user_id, name, position, is_default) values (?, ?, ?, 0, true)",
                UUID.randomUUID(), user, DEFAULT_NAME);
        }
        return jdbc.query("select id, name, position, is_default, updated_at from user_dashboard where user_id = ? "
                + "order by position, name",
            (rs, n) -> new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("position"),
                rs.getBoolean("is_default"), rs.getTimestamp("updated_at").toInstant()), user);
    }

    @Transactional(readOnly = true)
    public DashboardView get(UUID id) {
        return jdbc.query(VIEW_SQL + " where id = ? and user_id = ?", (rs, n) -> view(rs), id, context.userId())
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Dashboard no encontrado."));
    }

    @Transactional
    public DashboardView create(DashboardRequest r) {
        UUID user = context.userId();
        validate(r);
        requireFreeName(user, r.name().trim(), null);
        UUID id = UUID.randomUUID();
        insert(id, user, r.name().trim(), r.schemaVersion(), r.layouts(), r.widgets());
        return get(id);
    }

    @Transactional
    public DashboardView update(UUID id, DashboardRequest r) {
        UUID user = context.userId();
        get(id);
        validate(r);
        if (r.version() == null) throw new BusinessRuleException("Falta la versión del dashboard.");
        requireFreeName(user, r.name().trim(), id);
        int updated = jdbc.update("update user_dashboard set name = ?, schema_version = coalesce(?, schema_version), "
                + "layouts = cast(? as jsonb), widgets = cast(? as jsonb), version = version + 1, updated_at = now() "
                + "where id = ? and user_id = ? and version = ?",
            r.name().trim(), r.schemaVersion(), text(r.layouts(), "{}"), text(r.widgets(), "[]"), id, user, r.version());
        if (updated == 0) {
            throw new ConflictException("STALE_DASHBOARD",
                "El dashboard se ha modificado en otra ventana. Recarga para ver la última versión.");
        }
        return get(id);
    }

    @Transactional
    public DashboardView duplicate(UUID id) {
        UUID user = context.userId();
        DashboardView source = get(id);
        String name = source.name() + " (copia)";
        for (int n = 2; nameTaken(user, name, null); n++) name = source.name() + " (copia " + n + ")";
        if (name.length() > 80) name = name.substring(0, 80);
        UUID copy = UUID.randomUUID();
        insert(copy, user, name, source.schemaVersion(), source.layouts(), source.widgets());
        return get(copy);
    }

    @Transactional
    public void delete(UUID id) {
        UUID user = context.userId();
        DashboardView target = get(id);
        Integer count = jdbc.queryForObject("select count(*) from user_dashboard where user_id = ?", Integer.class, user);
        if (count != null && count <= 1) throw new BusinessRuleException("No puedes borrar tu único dashboard.");
        jdbc.update("delete from user_dashboard where id = ? and user_id = ?", id, user);
        if (target.isDefault()) {
            jdbc.update("update user_dashboard set is_default = true where id = (select id from user_dashboard "
                + "where user_id = ? order by position, name limit 1)", user);
        }
    }

    @Transactional
    public DashboardView makeDefault(UUID id) {
        UUID user = context.userId();
        get(id);
        jdbc.update("update user_dashboard set is_default = false where user_id = ? and is_default", user);
        jdbc.update("update user_dashboard set is_default = true where id = ? and user_id = ?", id, user);
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    private static final String VIEW_SQL = "select id, name, position, is_default, schema_version, layouts, widgets, version, "
        + "updated_at from user_dashboard";

    private DashboardView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new DashboardView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("position"),
            rs.getBoolean("is_default"), rs.getInt("schema_version"), json.readTree(rs.getString("layouts")),
            json.readTree(rs.getString("widgets")), rs.getInt("version"), updated.toInstant());
    }

    private void insert(UUID id, UUID user, String name, Integer schemaVersion, JsonNode layouts, JsonNode widgets) {
        Integer next = jdbc.queryForObject("select coalesce(max(position) + 1, 0) from user_dashboard where user_id = ?",
            Integer.class, user);
        Boolean first = jdbc.queryForObject("select not exists(select 1 from user_dashboard where user_id = ?)", Boolean.class, user);
        jdbc.update("insert into user_dashboard(id, user_id, name, position, schema_version, layouts, widgets, is_default) "
                + "values (?, ?, ?, ?, coalesce(?, 1), cast(? as jsonb), cast(? as jsonb), ?)",
            id, user, name, next, schemaVersion, text(layouts, "{}"), text(widgets, "[]"), Boolean.TRUE.equals(first));
    }

    private void validate(DashboardRequest r) {
        if (r.widgets() != null && !r.widgets().isArray()) throw new BusinessRuleException("El formato de paneles no es válido.");
        if (r.layouts() != null && !r.layouts().isObject()) throw new BusinessRuleException("El formato de paneles no es válido.");
        if (r.widgets() != null) {
            if (r.widgets().size() > MAX_WIDGETS) throw new BusinessRuleException("Máximo " + MAX_WIDGETS + " paneles por dashboard.");
            for (JsonNode widget : r.widgets()) {
                if (!widget.hasNonNull("id") || !widget.get("id").isString() || widget.get("id").asString().isBlank()
                    || !widget.hasNonNull("type") || !widget.get("type").isString() || widget.get("type").asString().isBlank()) {
                    throw new BusinessRuleException("Cada panel necesita id y tipo.");
                }
            }
        }
        if (text(r.widgets(), "[]").length() + text(r.layouts(), "{}").length() > MAX_JSON_CHARS) {
            throw new BusinessRuleException("El dashboard es demasiado grande.");
        }
    }

    private String text(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : json.writeValueAsString(node);
    }

    private boolean nameTaken(UUID user, String name, UUID excluding) {
        Integer n = jdbc.queryForObject("select count(*) from user_dashboard where user_id = ? and lower(name) = lower(?) "
                + "and (?::uuid is null or id <> ?::uuid)", Integer.class, user, name, excluding, excluding);
        return n != null && n > 0;
    }

    private void requireFreeName(UUID user, String name, UUID excluding) {
        if (nameTaken(user, name, excluding)) throw new ConflictException("DUPLICATE_NAME", "Ya tienes un dashboard con ese nombre.");
    }
}
