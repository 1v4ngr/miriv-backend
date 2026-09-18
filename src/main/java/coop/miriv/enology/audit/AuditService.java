package coop.miriv.enology.audit;

import coop.miriv.enology.identity.service.CurrentUserProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * F2-01: append-only audit log writer shared by every module.
 *
 * Every state-changing service calls {@link #record(String, UUID, String, String)} once it has
 * completed the operation. The row carries a stable {@code action} token, no longer than the
 * {@code audit_log.action varchar(20)} column allows (e.g. {@code ARCHIVE}, {@code GRANT}), the
 * entity's primary key, the actor (current user, when there is one) and an optional free-text
 * reason that explains WHY the change happened.
 *
 * The audit row is written in the SAME transaction as the business change so we never keep
 * a half-applied mutation; if the transaction rolls back, the audit entry rolls back too.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final JsonMapper json;

    public AuditService(JdbcTemplate jdbc, CurrentUserProvider currentUser, JsonMapper json) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityName, UUID entityId, String action, String reason) {
        record(entityName, entityId, action, reason, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityName, UUID entityId, String action, String reason,
                       Object previousValue, Object newValue) {
        jdbc.update("insert into audit_log(id, entity_name, entity_id, action, previous_value, "
                + "new_value, author_id, reason, created_at) "
                + "values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?)",
            UUID.randomUUID(), entityName, entityId, action,
            toJson(previousValue), toJson(newValue),
            // The scheduler (F4-04) runs with no authenticated user; author is then null,
            // never a thrown exception, so background evaluation can still audit its writes.
            currentUser.currentUserId().orElse(null), reason, Timestamp.from(Instant.now()));
    }

    /** Convenience: build an audit row whose {@code action} captures what kind of operation this was. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(String entityName, UUID entityId, String reason, Object previousValue, Object newValue) {
        record(entityName, entityId, "UPDATE", reason, previousValue, newValue);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("""
            select a.entity_name, a.entity_id, a.action, a.reason, a.created_at,
                   u.full_name as author, a.previous_value, a.new_value
              from audit_log a left join app_user u on u.id = a.author_id
             order by a.created_at desc
             limit ?
            """, limit);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        return json.writeValueAsString(value);
    }
}
