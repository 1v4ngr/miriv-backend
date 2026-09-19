package coop.miriv.enology.identity.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.PendingWorkResponse;
import coop.miriv.enology.identity.dto.PermissionGrantRequest;
import coop.miriv.enology.identity.dto.ResetPasswordRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private static final String ADMIN_ROLE_CODE = "ADMIN";
    static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    public UserAccountService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.encoder = encoder;
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
        if (account == null) throw new NotFoundException("Usuario no encontrado.");
        return account;
    }

    @Transactional
    public UserAccountResponse assignRole(UUID userId, RoleAssignmentRequest request) {
        requireNotSelf(userId, "No puedes cambiar tus propios roles: pide a otro administrador que lo haga.");
        requireMayManage(userId);
        UUID roleId = roleId(request.roleCode());
        UUID zoneId = request.zoneId();
        if (SUPER_ADMIN_ROLE_CODE.equals(request.roleCode())) {
            requireActorSuperAdmin("Solo un superadministrador puede conceder el rol de superadministrador.");
            if (zoneId != null) throw new BusinessRuleException("El rol de superadministrador no se limita a una zona.");
        }
        if (zoneId != null) {
            UUID zoneCenter = jdbc.queryForObject("select center_id from zone where id = ?", UUID.class, zoneId);
            UUID userCenter = jdbc.queryForObject("select center_id from app_user where id = ?", UUID.class, userId);
            if (zoneCenter == null || !zoneCenter.equals(userCenter)) {
                throw new AccessDeniedException("La zona pertenece a otro centro.");
            }
        }
        jdbc.update("insert into app_user_role(id, user_id, role_id, zone_id) values (?, ?, ?, ?)",
            UUID.randomUUID(), userId, roleId, zoneId);
        audit.record("app_user", userId, "ROLE_GRANTED", "Rol " + request.roleCode()
            + (zoneId == null ? " (todas las zonas)" : " (zona " + zoneId + ")"));
        return get(userId);
    }

    @Transactional
    public UserAccountResponse revokeRole(UUID userId, UUID roleAssignmentId) {
        requireNotSelf(userId, "No puedes cambiar tus propios roles: pide a otro administrador que lo haga.");
        requireMayManage(userId);
        String roleCode = jdbc.query("select r.code from app_user_role ur join role r on r.id = ur.role_id "
                + "where ur.id = ? and ur.user_id = ?", (rs, row) -> rs.getString(1), roleAssignmentId, userId)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Asignación de rol no encontrada."));
        if (SUPER_ADMIN_ROLE_CODE.equals(roleCode)) {
            requireActorSuperAdmin("Solo un superadministrador puede retirar el rol de superadministrador.");
            requireAnotherActiveSuperAdminRemains(userId);
        }
        if (ADMIN_ROLE_CODE.equals(roleCode) || SUPER_ADMIN_ROLE_CODE.equals(roleCode)) requireAnotherActiveAdminRemains(userId);
        int updated = jdbc.update("delete from app_user_role where id = ? and user_id = ?", roleAssignmentId, userId);
        if (updated == 0) throw new NotFoundException("Asignación de rol no encontrada.");
        audit.record("app_user", userId, "ROLE_REVOKED", "Asignación " + roleAssignmentId + " (" + roleCode + ")");
        return get(userId);
    }

    @Transactional
    public UserAccountResponse grantPermission(UUID userId, PermissionGrantRequest request) {
        requireNotSelf(userId, "No puedes concederte permisos a ti mismo: pide a otro administrador que lo haga.");
        requireMayManage(userId);
        UUID permissionId = permissionId(request.permissionCode());
        Boolean grantable = jdbc.queryForObject("select grantable from permission where id = ?", Boolean.class, permissionId);
        // A super administrator may grant any permission individually; other admins only the grantable ones.
        if (!Boolean.TRUE.equals(grantable) && !actorIsSuperAdmin()) {
            throw new ConflictException("Este permiso no se puede asignar individualmente a un usuario.");
        }
        UUID zoneId = request.zoneId();
        if (zoneId != null) {
            UUID zoneCenter = jdbc.queryForObject("select center_id from zone where id = ?", UUID.class, zoneId);
            UUID userCenter = jdbc.queryForObject("select center_id from app_user where id = ?", UUID.class, userId);
            if (zoneCenter == null || !zoneCenter.equals(userCenter)) {
                throw new AccessDeniedException("La zona pertenece a otro centro.");
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user_permission_grant(id, user_id, permission_id, zone_id, granted_by_id, reason, valid_from, valid_until) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, permissionId, zoneId, context.userId(),
            request.reason().trim(), Timestamp.from(request.validFrom() == null ? Instant.now() : request.validFrom()),
            request.validUntil() == null ? null : Timestamp.from(request.validUntil()));
        audit.record("app_user", userId, "GRANT", "Permiso " + request.permissionCode() + ": " + request.reason().trim());
        return get(userId);
    }

    @Transactional
    public UserAccountResponse revokePermission(UUID userId, UUID grantId) {
        requireNotSelf(userId, "No puedes cambiar tus propias concesiones: pide a otro administrador que lo haga.");
        requireMayManage(userId);
        Integer n = jdbc.update("delete from app_user_permission_grant where id = ? and user_id = ?", grantId, userId);
        if (n == null || n == 0) throw new NotFoundException("Otorgamiento de permiso no encontrado.");
        audit.record("app_user", userId, "REVOKE", "Concesión " + grantId);
        return get(userId);
    }

    @Transactional(readOnly = true)
    public PendingWorkResponse pendingWork(UUID userId) {
        List<String> tasks = jdbc.query("select code from task where responsible_id = ? "
                + "and status in ('PENDING'::task_status, 'IN_PROGRESS'::task_status) order by due_at nulls last",
            (rs, row) -> rs.getString(1), userId);
        List<String> incidents = jdbc.query("select code from incident where responsible_id = ? "
                + "and status not in ('RESOLVED'::incident_status, 'DISCARDED'::incident_status) order by opened_at",
            (rs, row) -> rs.getString(1), userId);
        return new PendingWorkResponse(tasks, incidents);
    }

    @Transactional
    public UserAccountResponse deactivate(UUID userId, UserAccountStatusRequest request) {
        if (userId.equals(context.userId())) {
            throw new ConflictException("No puedes desactivar tu propia cuenta: pide a otro administrador que lo haga.");
        }
        requireMayManage(userId);
        if (hasRole(userId, SUPER_ADMIN_ROLE_CODE)) requireAnotherActiveSuperAdminRemains(userId);
        if (isActiveAdmin(userId)) requireAnotherActiveAdminRemains(userId);
        int updated = jdbc.update("update app_user set active = false, deactivated_at = ? where id = ? and active = true",
            Timestamp.from(Instant.now()), userId);
        if (updated == 0) throw new NotFoundException("Usuario no encontrado o ya estaba inactivo.");
        audit.record("app_user", userId, "DEACTIVATE", request.reason().trim());
        return get(userId);
    }

    @Transactional
    public UserAccountResponse reactivate(UUID userId, UserAccountStatusRequest request) {
        requireMayManage(userId);
        int updated = jdbc.update("update app_user set active = true, deactivated_at = null where id = ? and active = false",
            userId);
        if (updated == 0) throw new NotFoundException("Usuario no encontrado o ya estaba activo.");
        audit.record("app_user", userId, "ACTIVATE", request.reason().trim());
        return get(userId);
    }

    @Transactional
    public void resetPassword(UUID userId, ResetPasswordRequest request) {
        requireMayManage(userId);
        int updated = jdbc.update("update app_user set password_hash = ? where id = ? and active = true",
            encoder.encode(request.newPassword()), userId);
        if (updated == 0) throw new NotFoundException("Usuario no encontrado o inactivo.");
        // Never write the password (or its hash) to the audit trail.
        audit.record("app_user", userId, "PASSWORD", "Contraseña restablecida por un administrador.");
    }

    /** Ordinary admins may not change their own access; a super administrator may (they already hold everything). */
    private void requireNotSelf(UUID userId, String message) {
        if (userId.equals(context.userId()) && !actorIsSuperAdmin()) throw new ConflictException(message);
    }

    /** Only a super administrator may change the account of a super administrator. */
    public void requireMayManage(UUID userId) {
        if (!userId.equals(context.userId()) && hasRole(userId, SUPER_ADMIN_ROLE_CODE) && !actorIsSuperAdmin()) {
            throw new AccessDeniedException("Solo un superadministrador puede modificar la cuenta de otro superadministrador.");
        }
    }

    private void requireActorSuperAdmin(String message) {
        if (!actorIsSuperAdmin()) throw new AccessDeniedException(message);
    }

    public boolean actorIsSuperAdmin() {
        UUID actor = context.userId();
        return actor != null && hasRole(actor, SUPER_ADMIN_ROLE_CODE);
    }

    private boolean hasRole(UUID userId, String roleCode) {
        Boolean has = jdbc.queryForObject("select exists(select 1 from app_user_role ur "
                + "join role r on r.id = ur.role_id where ur.user_id = ? and r.code = ?)",
            Boolean.class, userId, roleCode);
        return Boolean.TRUE.equals(has);
    }

    private boolean isActiveAdmin(UUID userId) {
        return hasRole(userId, ADMIN_ROLE_CODE) || hasRole(userId, SUPER_ADMIN_ROLE_CODE);
    }

    private void requireAnotherActiveSuperAdminRemains(UUID userIdBeingChanged) {
        Integer others = jdbc.queryForObject("select count(distinct u.id) from app_user u "
                + "join app_user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id "
                + "where r.code = ? and u.active = true and u.id <> ?", Integer.class, SUPER_ADMIN_ROLE_CODE, userIdBeingChanged);
        if (others == null || others == 0) {
            throw new BusinessRuleException("Debe quedar al menos un superadministrador activo.");
        }
    }

    /** Refuses the change when it would leave the cooperative with no active administrator. */
    private void requireAnotherActiveAdminRemains(UUID userIdBeingChanged) {
        Integer otherActiveAdmins = jdbc.queryForObject("select count(distinct u.id) from app_user u "
                + "join app_user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id "
                + "where r.code in (?, ?) and u.active = true and u.id <> ?", Integer.class,
            ADMIN_ROLE_CODE, SUPER_ADMIN_ROLE_CODE, userIdBeingChanged);
        if (otherActiveAdmins == null || otherActiveAdmins == 0) {
            throw new BusinessRuleException("Debe quedar al menos un administrador activo.");
        }
    }

    private UserAccountResponse loadAccount(UUID userId) {
        List<UserAccountResponse> accounts = jdbc.query("""
            select u.id, u.username, u.email, p.first_name, p.last_name, p.job_title, p.avatar_url,
                   u.active, u.created_at, u.deactivated_at,
                   coalesce(nullif(trim(concat(p.first_name, ' ', coalesce(p.last_name, ''))), ''), u.full_name) as display_name
              from app_user u left join user_profile p on p.user_id = u.id
             where u.id = ?
            """, (rs, n) -> new UserAccountResponse(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("display_name"),
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
            .orElseThrow(() -> new NotFoundException("Rol desconocido: " + code));
    }

    private UUID permissionId(String code) {
        return jdbc.query("select id from permission where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Permiso desconocido: " + code));
    }
}
