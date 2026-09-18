package coop.miriv.enology.identity.service;

import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.CenterMemberResponse;
import coop.miriv.enology.identity.dto.CenterOption;
import coop.miriv.enology.identity.dto.CurrentUserProfileResponse;
import coop.miriv.enology.identity.dto.UpdateProfileRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CurrentUserProfileService {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public CurrentUserProfileService(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public CurrentUserProfileResponse getCurrentProfile() {
        UUID userId = currentUser.requireCurrentUserId();
        var rows = jdbc.query("select u.username, u.email, p.first_name, p.last_name, p.avatar_url, p.job_title, "
                + "c.code as center_code, c.name as center_name from app_user u "
                + "join user_profile p on p.user_id = u.id left join center c on c.id = u.center_id "
                + "where u.id = ? and u.active = true", (rs, row) -> new ProfileRow(
                    rs.getString("username"), rs.getString("email"), rs.getString("first_name"),
                    rs.getString("last_name"), rs.getString("avatar_url"), rs.getString("job_title"),
                    rs.getString("center_code"), rs.getString("center_name")), userId);
        if (rows.isEmpty()) throw new NotFoundException("Current user profile not found.");
        ProfileRow profile = rows.getFirst();
        List<String> zones = jdbc.query("select distinct z.name from zone z where z.center_id = (select center_id from app_user where id = ?) order by z.name",
            (rs, row) -> rs.getString(1), userId);
        String displayName = (profile.firstName() + " " + profile.lastName()).trim();
        return new CurrentUserProfileResponse(profile.username(), profile.email(), profile.firstName(), profile.lastName(),
            displayName, profile.avatarUrl(), profile.jobTitle(), profile.centerCode(), profile.centerName(), zones);
    }

    @Transactional(readOnly = true)
    public List<CenterOption> listCenters() {
        UUID userId = currentUser.requireCurrentUserId();
        return jdbc.query("select c.code, c.name from center c "
                + "join app_user_center uc on uc.center_id = c.id "
                + "where uc.user_id = ? order by c.name",
            (rs, row) -> new CenterOption(rs.getString("code"), rs.getString("name")), userId);
    }

    @Transactional(readOnly = true)
    public List<CenterMemberResponse> listCenterMembers() {
        UUID currentUserId = currentUser.requireCurrentUserId();
        UUID centerId = jdbc.queryForObject("select center_id from app_user where id = ? and active = true", UUID.class, currentUserId);
        if (centerId == null) return List.of();
        return jdbc.query("""
                select u.username, u.email,
                       coalesce(nullif(trim(p.first_name || ' ' || p.last_name), ''), u.full_name) as display_name
                  from app_user u
                  left join user_profile p on p.user_id = u.id
                 where u.active = true and u.center_id = ?
                 order by display_name
                """,
            (rs, row) -> new CenterMemberResponse(rs.getString("username"), rs.getString("display_name"), rs.getString("email")),
            centerId);
    }

    @Transactional
    public CurrentUserProfileResponse updateCurrentProfile(UpdateProfileRequest request) {
        UUID userId = currentUser.requireCurrentUserId();
        int updated = jdbc.update(
            "update user_profile set first_name = ?, last_name = ?, job_title = ?, avatar_url = ?, updated_at = now() "
                + "where user_id = ?",
            request.firstName().trim(),
            StringUtils.hasText(request.lastName()) ? request.lastName().trim() : "",
            StringUtils.hasText(request.jobTitle()) ? request.jobTitle().trim() : null,
            StringUtils.hasText(request.avatarUrl()) ? request.avatarUrl().trim() : null,
            userId);
        if (updated == 0) throw new NotFoundException("Current user profile not found.");

        if (StringUtils.hasText(request.centerCode())) {
            List<UUID> centerIds = jdbc.query("select c.id from center c "
                    + "join app_user_center uc on uc.center_id = c.id "
                    + "where uc.user_id = ? and c.code = ?",
                (rs, row) -> rs.getObject(1, UUID.class), userId, request.centerCode().trim());
            if (centerIds.isEmpty()) throw new AccessDeniedException("No perteneces a ese centro.");
            jdbc.update("update app_user set center_id = ? where id = ?", centerIds.getFirst(), userId);
        }

        return getCurrentProfile();
    }

    private record ProfileRow(String username, String email, String firstName, String lastName, String avatarUrl,
                              String jobTitle, String centerCode, String centerName) {}
}
