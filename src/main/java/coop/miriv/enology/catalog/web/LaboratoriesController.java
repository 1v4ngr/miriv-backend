package coop.miriv.enology.catalog.web;

import coop.miriv.enology.catalog.dto.CatalogItemResponse;
import coop.miriv.enology.identity.service.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-ANA-04: list active destination laboratories available in the caller's center.
 * The catalog is intentionally read-only here; laboratory creation is a future admin feature.
 */
@RestController
@RequestMapping("/api/catalogs/laboratories")
public class LaboratoriesController {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public LaboratoriesController(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<CatalogItemResponse> list() {
        UUID userId = currentUser.requireCurrentUserId();
        UUID centerId = jdbc.queryForObject(
            "select center_id from app_user where id = ? and active = true", UUID.class, userId);
        if (centerId == null) return List.of();
        return jdbc.query(
            "select id, code, name, active from laboratory where center_id = ? and active = true order by name",
            (rs, index) -> new CatalogItemResponse(
                (UUID) rs.getObject("id"),
                rs.getString("code"),
                rs.getString("name"),
                null,
                rs.getBoolean("active")),
            centerId);
    }
}
