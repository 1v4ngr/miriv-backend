package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.entity.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AppUserPrincipal implements UserDetails {

    private final AppUser user;

    public AppUserPrincipal(AppUser user) {
        this.user = user;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public AppUser getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.getRoles().stream()
            .map(assignment -> new SimpleGrantedAuthority("ROLE_" + assignment.getRole().getCode()))
            .distinct()
            .toList();
    }

    public List<UUID> getScopedZoneIds() {
        return user.getRoles().stream()
            .map(assignment -> assignment.getZone())
            .filter(zone -> zone != null)
            .map(zone -> zone.getId())
            .toList();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
