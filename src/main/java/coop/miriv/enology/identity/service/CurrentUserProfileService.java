package coop.miriv.enology.identity.service;

import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.CurrentUserProfileResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        List<String> zones = jdbc.query("select distinct z.name from zone z where z.center_id = (select center_id from app_user where id = ?) "
                + "and (exists (select 1 from app_user_role ur where ur.user_id = ? and ur.zone_id is null) "
                + "or z.id in (select zone_id from app_user_role where user_id = ? and zone_id is not null)) order by z.name",
            (rs, row) -> rs.getString(1), userId, userId, userId);
        String displayName = (profile.firstName() + " " + profile.lastName()).trim();
        return new CurrentUserProfileResponse(profile.username(), profile.email(), profile.firstName(), profile.lastName(),
            displayName, profile.avatarUrl(), profile.jobTitle(), profile.centerCode(), profile.centerName(), zones);
    }

    private record ProfileRow(String username, String email, String firstName, String lastName, String avatarUrl,
                              String jobTitle, String centerCode, String centerName) {}
}
