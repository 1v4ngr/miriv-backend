package coop.miriv.enology.identity.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.AdminUserResponse;
import coop.miriv.enology.identity.dto.CenterOption;
import coop.miriv.enology.identity.dto.CreateAdminUserRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AdminUserService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminUserService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional public AdminUserResponse create(CreateAdminUserRequest r) {
        UUID centerId=jdbc.queryForObject("select id from center where code=?",UUID.class,r.centerCodes().getFirst());
        UUID id=UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,email,full_name,password_hash,center_id) values(?,?,?,?,?,?)",id,r.username().trim(),r.email().trim(),(r.firstName()+" "+(r.lastName()==null?"":r.lastName())).trim(),encoder.encode(r.password()),centerId);
        jdbc.update("insert into user_profile(user_id,first_name,last_name,job_title) values(?,?,?,?)",id,r.firstName().trim(),r.lastName()==null?"":r.lastName().trim(),r.jobTitle());
        r.centerCodes().forEach(c->jdbc.update("insert into app_user_center(user_id,center_id) select ?,id from center where code=?",id,c));
        // A user is never created without any role (B5): default to VIEWER if none were requested.
        List<String> roleCodes = r.roleCodes() == null || r.roleCodes().isEmpty() ? List.of("VIEWER") : r.roleCodes();
        for (String roleCode : roleCodes) {
            jdbc.update("insert into app_user_role(id, user_id, role_id) select ?, ?, id from role where code = ?",
                UUID.randomUUID(), id, roleCode);
        }
        audit.record("app_user", id, "USER_CREATED", "Creado " + r.username().trim() + " con roles " + String.join(", ", roleCodes));
        return list().stream().filter(u->u.username().equals(r.username().trim())).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return jdbc.query("select u.id, u.username, u.email, coalesce(p.first_name || ' ' || p.last_name, u.full_name) display_name, "
                + "pc.code primary_center from app_user u left join user_profile p on p.user_id = u.id "
                + "left join center pc on pc.id = u.center_id where u.active = true order by display_name",
            (rs, row) -> response(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("primary_center")));
    }

    @Transactional
    public AdminUserResponse updateCenters(String username, List<String> centerCodes) {
        UUID userId = jdbc.query("select id from app_user where lower(username) = lower(?) and active = true",
            (rs, row) -> rs.getObject(1, UUID.class), username).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));
        List<String> normalized = centerCodes.stream().map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) throw new BusinessRuleException("Es obligatorio al menos un centro.");
        String placeholders = String.join(",", normalized.stream().map(code -> "?").toList());
        Integer valid = jdbc.queryForObject("select count(*) from center where code in (" + placeholders + ")", Integer.class,
            normalized.toArray());
        if (valid == null || valid != normalized.size()) throw new NotFoundException("Uno o más centros no existen.");
        jdbc.update("delete from app_user_center where user_id = ?", userId);
        normalized.forEach(code -> jdbc.update("insert into app_user_center (user_id, center_id) select ?, id from center where code = ?", userId, code));
        jdbc.update("update app_user set center_id = (select id from center where code = ?) where id = ?", normalized.getFirst(), userId);
        audit.record("app_user", userId, "USER_CENTERS_UPDATED", "Centros: " + String.join(", ", normalized));
        return list().stream().filter(item -> item.username().equalsIgnoreCase(username)).findFirst().orElseThrow();
    }

    private AdminUserResponse response(UUID id, String username, String email, String displayName, String primary) {
        List<CenterOption> centers = jdbc.query("select c.code, c.name from app_user_center uc join center c on c.id = uc.center_id where uc.user_id = ? order by c.name",
            (rs, row) -> new CenterOption(rs.getString("code"), rs.getString("name")), id);
        return new AdminUserResponse(username, displayName, email, primary, centers);
    }
}
