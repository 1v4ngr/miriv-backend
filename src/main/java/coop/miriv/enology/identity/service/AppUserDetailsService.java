package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.entity.AppUser;
import coop.miriv.enology.identity.repository.AppUserRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;
    private final JdbcTemplate jdbc;

    public AppUserDetailsService(AppUserRepository appUserRepository, JdbcTemplate jdbc) {
        this.appUserRepository = appUserRepository;
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = appUserRepository.findByUsernameAndActiveTrue(username)
            .or(() -> appUserRepository.findByEmailIgnoreCaseAndActiveTrue(username))
            .orElseThrow(() -> new UsernameNotFoundException("Unknown or inactive user: " + username));
        return loadWithPermissions(user);
    }

    public AppUserPrincipal loadWithPermissions(AppUser user) {
        Map<String, Boolean> all = new HashMap<>();
        Map<String, Set<UUID>> zones = new HashMap<>();
        jdbc.query("""
            select p.code, ur.zone_id from app_user_role ur
              join role_permission rp on rp.role_id = ur.role_id
              join permission p on p.id = rp.permission_id
             where ur.user_id = ?
            union all
            select p.code, g.zone_id from app_user_permission_grant g
              join permission p on p.id = g.permission_id
             where g.user_id = ? and g.valid_from <= now() and (g.valid_until is null or g.valid_until > now())
            """, rs -> {
                String code = rs.getString(1);
                UUID zone = rs.getObject(2, UUID.class);
                if (zone == null) all.put(code, true);
                else zones.computeIfAbsent(code, key -> new HashSet<>()).add(zone);
            }, user.getId(), user.getId());
        Map<String, PermissionScope> permissions = new HashMap<>();
        Stream.concat(all.keySet().stream(), zones.keySet().stream()).distinct().forEach(code ->
            permissions.put(code, new PermissionScope(all.getOrDefault(code, false), zones.getOrDefault(code, Set.of()))));
        boolean readAll = user.getRoles().stream().anyMatch(role -> role.getZone() == null);
        Set<UUID> readZones = user.getRoles().stream().filter(role -> role.getZone() != null)
            .map(role -> role.getZone().getId()).collect(Collectors.toSet());
        return new AppUserPrincipal(user, permissions, new PermissionScope(readAll, readZones));
    }
}