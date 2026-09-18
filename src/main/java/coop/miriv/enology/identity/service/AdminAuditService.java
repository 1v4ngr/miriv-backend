package coop.miriv.enology.identity.service;

import coop.miriv.enology.identity.dto.AuditEntryResponse;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {

    private final JdbcTemplate jdbc;

    public AdminAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditEntryResponse> list() {
        return jdbc.query("select a.entity_name, a.action, coalesce(u.full_name, 'Sistema') author, a.reason, a.created_at "
                + "from audit_log a left join app_user u on u.id = a.author_id order by a.created_at desc limit 100",
            (rs, n) -> new AuditEntryResponse(rs.getString("entity_name"), rs.getString("action"),
                rs.getString("author"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()));
    }
}