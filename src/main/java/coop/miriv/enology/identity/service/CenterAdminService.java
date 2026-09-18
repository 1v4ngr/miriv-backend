package coop.miriv.enology.identity.service;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.dto.CenterAdminRequest;
import coop.miriv.enology.identity.dto.CenterOption;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CenterAdminService {

    private final JdbcTemplate jdbc;

    public CenterAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CenterOption> list() {
        return jdbc.query("select code, name from center order by name",
            (rs, n) -> new CenterOption(rs.getString(1), rs.getString(2)));
    }

    @Transactional
    public CenterOption create(CenterAdminRequest r) {
        try {
            jdbc.update("insert into center(code, name) values(?, ?)", r.code().trim().toUpperCase(), r.name().trim());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("Center code already exists.");
        }
        return new CenterOption(r.code().trim().toUpperCase(), r.name().trim());
    }

    @Transactional
    public CenterOption update(String code, CenterAdminRequest r) {
        int n = jdbc.update("update center set code = ?, name = ? where code = ?",
            r.code().trim().toUpperCase(), r.name().trim(), code);
        if (n == 0) throw new NotFoundException("Center not found.");
        return new CenterOption(r.code().trim().toUpperCase(), r.name().trim());
    }

    @Transactional
    public void delete(String code) {
        UUID id = jdbc.query("select id from center where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Center not found."));
        Integer refs = jdbc.queryForObject("select (select count(*) from app_user where center_id = ?) "
            + "+ (select count(*) from zone where center_id = ?) + (select count(*) from deposit where center_id = ?)",
            Integer.class, id, id, id);
        if (refs != null && refs > 0) throw new ConflictException("The center is still referenced by users, zones or deposits.");
        jdbc.update("delete from center where id = ?", id);
    }
}