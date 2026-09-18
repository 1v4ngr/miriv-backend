package coop.miriv.enology.identity.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public Optional<UUID> currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
            ? null
            : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AppUserPrincipal appUserPrincipal) {
            return Optional.of(appUserPrincipal.getUserId());
        }
        return Optional.empty();
    }

    public UUID requireCurrentUserId() {
        return currentUserId().orElseThrow(() -> new IllegalStateException("No hay un usuario autenticado en el contexto."));
    }
}
