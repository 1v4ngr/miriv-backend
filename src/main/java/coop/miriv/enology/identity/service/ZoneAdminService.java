package coop.miriv.enology.identity.service;

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

    public ZoneAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        jdbc.update("insert into zone(code, name, center_id) values(?, ?, ?)",
            r.code().trim().toUpperCase(), r.name().trim(), center);
        return Map.of("code", r.code().trim().toUpperCase(), "name", r.name().trim(), "centerCode", r.centerCode());
    }

    @Transactional
    public void update(String code, ZoneAdminRequest r) {
        UUID center = jdbc.queryForObject("select id from center where code = ?", UUID.class, r.centerCode());
        int n = jdbc.update("update zone set code = ?, name = ?, center_id = ? where code = ?",
            r.code().trim().toUpperCase(), r.name().trim(), center, code);
        if (n == 0) throw new NotFoundException("Zone not found.");
    }

    @Transactional
    public void delete(String code) {
        try {
            if (jdbc.update("delete from zone where code = ?", code) == 0) throw new NotFoundException("Zone not found.");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("The zone is still referenced by roles or deposits.");
        }
    }
}