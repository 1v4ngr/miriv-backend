package coop.miriv.enology.identity.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.ZoneAdminRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneAdminService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ZoneAdminService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Map<String, String>> list() {
        return jdbc.query("select z.code, z.name, c.code center_code, c.name center_name "
                + "from zone z join center c on c.id = z.center_id order by c.name, z.name",
            (rs, n) -> Map.of(
                "code", rs.getString("code"),
                "name", rs.getString("name"),
                "centerCode", rs.getString("center_code"),
                "centerName", rs.getString("center_name")));
    }

    @Transactional
    public Map<String, String> create(ZoneAdminRequest r) {
        UUID center = jdbc.queryForObject("select id from center where code = ?", UUID.class, r.centerCode());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into zone(id, code, name, center_id) values(?, ?, ?, ?)",
            id, r.code().trim().toUpperCase(), r.name().trim(), center);
        audit.record("zone", id, "ZONE_CREATED", r.code().trim().toUpperCase() + " en " + r.centerCode());
        return Map.of("code", r.code().trim().toUpperCase(), "name", r.name().trim(), "centerCode", r.centerCode());
    }

    @Transactional
    public void update(String code, ZoneAdminRequest r) {
        UUID center = jdbc.queryForObject("select id from center where code = ?", UUID.class, r.centerCode());
        UUID id = jdbc.query("select id from zone where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Zona no encontrada."));
        jdbc.update("update zone set code = ?, name = ?, center_id = ? where id = ?",
            r.code().trim().toUpperCase(), r.name().trim(), center, id);
        audit.record("zone", id, "ZONE_UPDATED", "Renombrada a " + r.name().trim());
    }

    @Transactional
    public void delete(String code) {
        UUID id = jdbc.query("select id from zone where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Zona no encontrada."));
        try {
            jdbc.update("delete from zone where id = ?", id);
            audit.record("zone", id, "ZONE_DELETED", code);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("La zona sigue siendo referenciada por roles o depósitos.");
        }
    }
}
