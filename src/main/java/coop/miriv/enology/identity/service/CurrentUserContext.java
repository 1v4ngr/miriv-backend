package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.entity.Center;
import coop.miriv.enology.identity.repository.AppUserRepository;
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

    private AppUserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new AccessDeniedException("Sesión no válida.");
        Object value = authentication.getPrincipal();
        if (value instanceof AppUserPrincipal principal) return principal;
        throw new AccessDeniedException("Sesión no válida.");
    }

    public boolean has(String permission) {
        return principal().getPermissions().containsKey(permission);
    }
}