package coop.miriv.enology.identity.service;

import coop.miriv.enology.audit.AuditService;
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
    private final AuditService audit;

    public CenterAdminService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<CenterOption> list() {
        return jdbc.query("select code, name from center order by name",
            (rs, n) -> new CenterOption(rs.getString(1), rs.getString(2)));
    }

    @Transactional
    public CenterOption create(CenterAdminRequest r) {
        String code = r.code().trim().toUpperCase();
        try {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into center(id, code, name) values(?, ?, ?)", id, code, r.name().trim());
            audit.record("center", id, "CENTER_CREATED", code);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("DUPLICATE_CODE", "Ya existe un centro con ese código.");
        }
        return new CenterOption(code, r.name().trim());
    }

    @Transactional
    public CenterOption update(String code, CenterAdminRequest r) {
        UUID id = jdbc.query("select id from center where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Centro no encontrado."));
        jdbc.update("update center set code = ?, name = ? where id = ?",
            r.code().trim().toUpperCase(), r.name().trim(), id);
        audit.record("center", id, "CENTER_UPDATED", "Renombrado a " + r.name().trim());
        return new CenterOption(r.code().trim().toUpperCase(), r.name().trim());
    }

    @Transactional
    public void delete(String code) {
        UUID id = jdbc.query("select id from center where code = ?",
            (rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Centro no encontrado."));
        Integer refs = jdbc.queryForObject("select (select count(*) from app_user where center_id = ?) "
            + "+ (select count(*) from zone where center_id = ?) + (select count(*) from deposit where center_id = ?)",
            Integer.class, id, id, id);
        if (refs != null && refs > 0) throw new ConflictException("CENTER_NOT_EMPTY", "El centro tiene usuarios, zonas o depósitos. Un superadministrador puede eliminarlo con todo su contenido.");
        jdbc.update("delete from center where id = ?", id);
        audit.record("center", id, "CENTER_DELETED", code);
    }
}
