package coop.miriv.enology.laboratory.service;

import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.laboratory.dto.ImportDto.TemplateColumn;
import coop.miriv.enology.laboratory.dto.ImportDto.TemplateRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.TemplateResponse;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Column matchings saved per centre, so the recurring analyser sheet only has to be mapped once.
 * Saving under an existing name overwrites it; that is how a template is corrected.
 */
@Service
public class ImportTemplateService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final JsonMapper json;

    public ImportTemplateService(JdbcTemplate jdbc, CurrentUserContext context, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list() {
        return jdbc.query("select t.name, t.columns, u.full_name as author, t.updated_at "
                + "from analysis_import_template t join app_user u on u.id = t.author_id "
                + "where t.center_id = ? order by t.updated_at desc",
            (rs, index) -> new TemplateResponse(rs.getString("name"),
                json.readValue(rs.getString("columns"), new TypeReference<List<TemplateColumn>>() {}),
                rs.getString("author"), rs.getTimestamp("updated_at").toInstant()),
            context.centerId());
    }

    @Transactional
    public TemplateResponse save(TemplateRequest request) {
        jdbc.update("insert into analysis_import_template(center_id, name, columns, author_id) "
                + "values (?, ?, cast(? as jsonb), ?) "
                + "on conflict (center_id, name) do update set columns = excluded.columns, "
                + "author_id = excluded.author_id, updated_at = now()",
            context.centerId(), request.name().trim(), json.writeValueAsString(request.columns()), context.userId());
        return list().stream()
            .filter(template -> template.name().equals(request.name().trim()))
            .findFirst()
            .orElseThrow();
    }

    @Transactional
    public void delete(String name) {
        jdbc.update("delete from analysis_import_template where center_id = ? and name = ?", context.centerId(), name);
    }
}
