package coop.miriv.enology.identity.web;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminPermissionController {

    private final JdbcTemplate jdbc;

    public AdminPermissionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles() {
        return jdbc.query("select code, name, description from role order by name",
            (rs, n) -> Map.of(
                "code", rs.getString("code"),
                "name", rs.getString("name"),
                "description", rs.getString("description")));
    }

    @GetMapping("/permissions")
    public List<Map<String, Object>> listPermissions() {
        return jdbc.query("select code, group_name, description, grantable from permission order by group_name, code",
            (rs, n) -> Map.of(
                "code", rs.getString("code"),
                "groupName", rs.getString("group_name"),
                "description", rs.getString("description"),
                "grantable", rs.getBoolean("grantable")));
    }
}