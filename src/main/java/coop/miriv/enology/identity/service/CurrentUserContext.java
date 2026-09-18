package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.entity.Center;
import coop.miriv.enology.identity.repository.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Single place to resolve the authenticated user and the center they are working in. */
@Component
public class CurrentUserContext {

    private final AppUserRepository users;
    private final CurrentUserProvider provider;

    public CurrentUserContext(AppUserRepository users, CurrentUserProvider provider) {
        this.users = users;
        this.provider = provider;
    }

    public UUID userId() {
        return provider.requireCurrentUserId();
    }

    public AppUser user() {
        return users.findById(userId()).filter(AppUser::isActive)
            .orElseThrow(() -> new AccessDeniedException("El usuario actual no está activo."));
    }

    public Center center() {
        AppUser user = user();
        if (user.getCenter() == null) throw new AccessDeniedException("El usuario actual no tiene centro asignado.");
        return user.getCenter();
    }

    public UUID centerId() {
        return center().getId();
    }

    public boolean has(String permission) {
        return principal().getPermissions().containsKey(permission);
    }

    public boolean hasInZone(String permission, UUID zoneId) {
        PermissionScope scope = principal().getPermissions().get(permission);
        return scope != null && scope.covers(zoneId);
    }

    public void requireInZone(String permission, UUID zoneId) {
        if (!hasInZone(permission, zoneId)) {
            throw new AccessDeniedException("No tienes permiso para esta acción en esa zona.");
        }
    }

    public boolean canRead(UUID zoneId) {
        return principal().getReadScope().covers(zoneId);
    }

    /** SQL fragment restricting a deposit alias to the zones the user may read; empty when all zones are allowed. */
    public ZoneFilter readZoneFilter(String depositAlias) {
        PermissionScope scope = principal().getReadScope();
        if (scope.allZones()) return new ZoneFilter(true, List.of(), "");
        if (scope.zoneIds().isEmpty()) return new ZoneFilter(false, List.of(), " and false");
        String marks = String.join(",", scope.zoneIds().stream().map(id -> "?").toList());
        return new ZoneFilter(false, List.copyOf(scope.zoneIds()), " and " + depositAlias + ".zone_id in (" + marks + ")");
    }

    public record ZoneFilter(boolean allZones, List<UUID> zoneIds, String sql) {}

    private AppUserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new AccessDeniedException("Sesión no válida.");
        Object value = authentication.getPrincipal();
        if (value instanceof AppUserPrincipal principal) return principal;
        throw new AccessDeniedException("Sesión no válida.");
    }
}