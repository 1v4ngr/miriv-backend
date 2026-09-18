package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.entity.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AppUserPrincipal implements UserDetails {

    private final AppUser user;
    private final Map<String, PermissionScope> permissions;
    private final PermissionScope readScope;

    public AppUserPrincipal(AppUser user) {
        this(user, Map.of(), new PermissionScope(true, Set.of()));
    }

    public AppUserPrincipal(AppUser user, Map<String, PermissionScope> permissions, PermissionScope readScope) {
        this.user = user;
        this.permissions = permissions;
        this.readScope = readScope;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public AppUser getUser() {
        return user;
    }

    public Map<String, PermissionScope> getPermissions() {
        return permissions;
    }

    public PermissionScope getReadScope() {
        return readScope;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Stream.concat(
                user.getRoles().stream().map(assignment -> "ROLE_" + assignment.getRole().getCode()),
                permissions.keySet().stream().map(code -> "PERM_" + code))
            .distinct()
            .map(SimpleGrantedAuthority::new)
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