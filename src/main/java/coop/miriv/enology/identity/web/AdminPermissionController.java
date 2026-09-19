package coop.miriv.enology.identity.web;

import java.util.List;
import java.util.Map;
import coop.miriv.enology.identity.service.UserAccountService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminPermissionController {

    private final JdbcTemplate jdbc;
    private final UserAccountService accounts;

    public AdminPermissionController(JdbcTemplate jdbc, UserAccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles() {
        // SUPER_ADMIN is only offered to someone who can actually grant it.
        String filter = accounts.actorIsSuperAdmin() ? "" : " where code <> 'SUPER_ADMIN'";
        return jdbc.query("select code, name, description from role" + filter + " order by name",
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