package coop.miriv.enology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import coop.miriv.enology.identity.dto.CenterOption;
import coop.miriv.enology.identity.dto.CurrentUserProfileResponse;
import coop.miriv.enology.identity.dto.UpdateProfileRequest;
import coop.miriv.enology.identity.repository.AppUserRepository;
import coop.miriv.enology.identity.service.AppUserPrincipal;
import coop.miriv.enology.identity.service.CurrentUserProfileService;
import coop.miriv.enology.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AccountSecurityTest extends IntegrationTest {

    @Autowired CurrentUserProfileService profileService;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;

    @BeforeEach
    void authenticate() {
        AppUserPrincipal principal = new AppUserPrincipal(users.findByUsernameAndActiveTrue("enologo").orElseThrow());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void userCanOnlyChangeToCentersWhereTheyAreMember() {
        UUID centroNorte = jdbc.queryForObject("select id from center where code = 'CENTRO-NORTE'", UUID.class);
        UUID foreignCenterId = UUID.randomUUID();
        jdbc.update("insert into center(id, code, name) values (?, 'OTRO', 'Centro OTRO')", foreignCenterId);

        List<CenterOption> ownCenters = profileService.listCenters();
        assertFalse(ownCenters.stream().anyMatch(option -> option.code().equals("OTRO")),
            "OTRO must not appear because the user has no membership there.");

        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
            () -> profileService.updateCurrentProfile(new UpdateProfileRequest(
                "Elena", "Enóloga", null, null, "OTRO")));
        assertEquals("No perteneces a ese centro.", denied.getMessage());

        CurrentUserProfileResponse sameCenter = profileService.updateCurrentProfile(new UpdateProfileRequest(
            "Elena", "Enóloga", null, null, "CENTRO-NORTE"));
        assertEquals(centroNorte, jdbc.queryForObject("select center_id from app_user where username = 'enologo'", UUID.class));
        assertEquals("CENTRO-NORTE", sameCenter.centerCode());
    }
}