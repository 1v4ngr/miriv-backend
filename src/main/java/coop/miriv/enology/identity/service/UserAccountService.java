package coop.miriv.enology.identity.service;

import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.PermissionGrantRequest;
import coop.miriv.enology.identity.dto.RoleAssignmentRequest;
import coop.miriv.enology.identity.dto.UserAccountResponse;
import coop.miriv.enology.identity.dto.UserAccountStatusRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;

    public UserAccountService(JdbcTemplate jdbc, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public List<UserAccountResponse> list() {
        List<UUID> ids = jdbc.query("select id from app_user where active = true order by username",
            (rs, n) -> rs.getObject(1, UUID.class));
        List<UserAccountResponse> out = new ArrayList<>();
        for (UUID id : ids) out.add(get(id));
        return out;
    }

    @Transactional(readOnly = true)
    public UserAccountResponse get(UUID id) {
        UserAccountResponse account = loadAccount(id);
        if (account == null) throw new NotFoundException("User not found.");
        return account;
    }

    @Transactional
    public UserAccountResponse assignRole(UUID userId, RoleAssignmentRequest request) {
        UUID roleId = roleId(request.roleCode());
        UUID zoneId = request.zoneId();
        if (zoneId != null) {
            UUID zoneCenter = jdbc.queryForObject("select center_id from zone where id = ?", UUID.class, zoneId);
            UUID userCenter = jdbc.queryForObject("select center_id from app_user where id = ?", UUID.class, userId);
            if (!zoneCenter.equals(userCenter)) {
                throw new AccessDeniedException("Zone belongs to another center.");
            }
        }
        jdbc.update("insert into app_user_role(id, user_id, role_id, zone_id) values (?, ?, ?, ?)",
            UUID.randomUUID(), userId, roleId, zoneId);
        audit("USER_ROLE_GRANTED", userId, "Granted role " + request.roleCode() + " (zone=" + zoneId + ")");
        return get(userId);
    }

    @Transactional
    public UserAccountResponse revokeRole(UUID userId, UUID roleAssignmentId) {
        Integer n = jdbc.query("delete from app_user_role where id = ? and user_id = ? returning role_id",
            (rs, row) -> rs.getObject(1, UUID.class), roleAssignmentId, userId).size();
        if (n == null || n == 0) throw new NotFoundException("Role assignment not found.");
        audit("USER_ROLE_REVOKED", userId, "Revoked role assignment " + roleAssignmentId);
        return get(userId);
    }

    @Transactional
    public UserAccountResponse grantPermission(UUID userId, PermissionGrantRequest request) {
        UUID permissionId = permissionId(request.permissionCode());
        if (!jdbc.queryForObject("select grantable from permission where id = ?", Boolean.class, permissionId)) {
            throw new ConflictException("This permission is not grantable to individual users.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user_permission_grant(id, user_id, permission_id, zone_id, granted_by_id, reason, valid_from, valid_until) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, permissionId, request.zoneId(), context.userId(),
            request.reason().trim(), Timestamp.from(request.validFrom() == null ? Instant.now() : request.validFrom()),
            request.validUntil() == null ? null : Timestamp.from(request.validUntil()));
        audit("USER_PERMISSION_GRANTED", userId, "Granted permission " + request.permissionCode() + " (zone=" + request.zoneId() + ")");
        return get(userId);
    }

    @Transactional
    public UserAccountResponse revokePermission(UUID userId, UUID grantId) {
        Integer n = jdbc.update("delete from app_user_permission_grant where id = ? and user_id = ?", grantId, userId);
        if (n == null || n == 0) throw new NotFoundException("Permission grant not found.");
        audit("USER_PERMISSION_REVOKED", userId, "Revoked permission grant " + grantId);
        return get(userId);
    }

    @Transactional
    public UserAccountResponse deactivate(UUID userId, UserAccountStatusRequest request) {
        if (userId.equals(context.userId())) throw new ConflictException("You cannot deactivate your own account.");
        int updated = jdbc.update("update app_user set active = false, deactivated_at = ? where id = ? and active = true",
            Timestamp.from(Instant.now()), userId);
        if (updated == 0) throw new NotFoundException("User not found or already inactive.");
        audit("USER_DEACTIVATED", userId, request.reason().trim());
        return get(userId);
    }

    @Transactional
    public UserAccountResponse reactivate(UUID userId, UserAccountStatusRequest request) {
        int updated = jdbc.update("update app_user set active = true, deactivated_at = null where id = ? and active = false",
            userId);
        if (updated == 0) throw new NotFoundException("User not found or already active.");
        audit("USER_REACTIVATED", userId, request.reason().trim());
        return get(userId);
    }

    private UserAccountResponse loadAccount(UUID userId) {
        List<UserAccountResponse> accounts = jdbc.query("""
            select u.id, u.username, u.email, p.first_name, p.last_name, p.job_title, p.avatar_url,
                   u.active, u.created_at, u.deactivated_at,
                   (u.first_name || ' ' || coalesce(p.last_name, '')) as display_name
              from app_user u left join user_profile p on p.user_id = u.id
             where u.id = ?
            """, (rs, n) -> new UserAccountResponse(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("display_name").trim(),
                rs.getString("job_title"), rs.getString("avatar_url"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("deactivated_at") == null ? null : rs.getTimestamp("deactivated_at").toInstant(),
                List.of(), List.of(), List.of()),
            userId);
        if (accounts.isEmpty()) return null;
        UserAccountResponse base = accounts.getFirst();
        return new UserAccountResponse(base.id(), base.username(), base.email(),
            base.firstName(), base.lastName(), base.displayName(), base.jobTitle(), base.avatarUrl(),
            base.active(), base.createdAt(), base.deactivatedAt(),
            listRoles(userId), listGrants(userId), listCenters(userId));
    }

    private List<UserAccountResponse.RoleSummary> listRoles(UUID userId) {
        return jdbc.query("select ur.id, ur.role_id, r.code, r.name, ur.zone_id, z.code as zone_code, z.name as zone_name "
                + "from app_user_role ur join role r on r.id = ur.role_id "
                + "left join zone z on z.id = ur.zone_id where ur.user_id = ? order by r.name",
            (rs, n) -> new UserAccountResponse.RoleSummary(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"),
                rs.getObject("zone_id", UUID.class), rs.getString("zone_code"), rs.getString("zone_name")),
            userId);
    }

    private List<UserAccountResponse.PermissionGrantSummary> listGrants(UUID userId) {
        return jdbc.query("select g.id, p.code, g.zone_id, z.code as zone_code, g.granted_by_id, "
                + "gu.username as granted_by_username, g.reason, g.valid_from, g.valid_until "
                + "from app_user_permission_grant g join permission p on p.id = g.permission_id "
                + "left join zone z on z.id = g.zone_id "
                + "left join app_user gu on gu.id = g.granted_by_id "
                + "where g.user_id = ? order by p.code, g.created_at",
            (rs, n) -> new UserAccountResponse.PermissionGrantSummary(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("zone_id", UUID.class), rs.getString("zone_code"),
                rs.getObject("granted_by_id", UUID.class), rs.getString("granted_by_username"),
                rs.getString("reason"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant()),
            userId);
    }

    private List<UserAccountResponse.CenterMembershipSummary> listCenters(UUID userId) {
        return jdbc.query("select c.id, c.code, c.name from app_user_center uc join center c on c.id = uc.center_id "
                + "where uc.user_id = ? order by c.name",
            (rs, n) -> new UserAccountResponse.CenterMembershipSummary(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")),
            userId);
    }

    private UUID roleId(String code) {
        return jdbc.query("select id from role where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Unknown role: " + code));
    }

    private UUID permissionId(String code) {
        return jdbc.query("select id from permission where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Unknown permission: " + code));
    }

    private void audit(String action, UUID userId, String reason) {
        jdbc.update("insert into audit_log(id, entity_name, entity_id, action, author_id, reason, created_at) "
                + "values (?, 'app_user', ?, ?, ?, ?, ?)",
            UUID.randomUUID(), userId, action, context.userId(), reason, Timestamp.from(Instant.now()));
    }
}