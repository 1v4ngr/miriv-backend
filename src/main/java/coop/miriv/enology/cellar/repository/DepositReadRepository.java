package coop.miriv.enology.cellar.repository;

import coop.miriv.enology.cellar.dto.CleaningRecordResponse;
import coop.miriv.enology.cellar.dto.OccupationResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DepositReadRepository {

    private final JdbcTemplate jdbc;

    public DepositReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, List<OccupationResponse>> occupationsByCenter(UUID centerId) {
        Map<UUID, List<OccupationResponse>> result = new HashMap<>();
        jdbc.query("""
            select o.deposit_id, c.code as content_code, l.code as lot_code,
                   o.start_at, o.end_at, o.volume_liters, coalesce(cat.name, 'Unclassified') as category
              from occupation o
              join deposit d on d.id = o.deposit_id
              join content_unit c on c.id = o.content_unit_id
              join lot l on l.id = c.lot_id
              left join internal_category cat on cat.id = c.category_id
             where d.center_id = ?
             order by o.start_at desc
            """, rs -> {
                UUID depositId = rs.getObject("deposit_id", UUID.class);
                OccupationResponse occupation = new OccupationResponse(
                    rs.getString("content_code"), rs.getString("lot_code"),
                    rs.getTimestamp("start_at").toInstant(), instantOrNull(rs.getTimestamp("end_at")),
                    rs.getBigDecimal("volume_liters"), rs.getString("category"),
                    "Not evaluated", "Not evaluated");
                result.computeIfAbsent(depositId, ignored -> new ArrayList<>()).add(occupation);
            }, centerId);
        return result;
    }

    public Map<UUID, List<CleaningRecordResponse>> cleaningByCenter(UUID centerId) {
        Map<UUID, List<CleaningRecordResponse>> result = new HashMap<>();
        jdbc.query("""
            select r.deposit_id, r.performed_at, r.action, u.full_name, r.result
              from deposit_cleaning_record r
              join deposit d on d.id = r.deposit_id
              left join app_user u on u.id = r.responsible_id
             where d.center_id = ?
             order by r.performed_at desc
            """, rs -> {
                UUID depositId = rs.getObject("deposit_id", UUID.class);
                CleaningRecordResponse record = new CleaningRecordResponse(
                    rs.getTimestamp("performed_at").toInstant(), rs.getString("action"),
                    rs.getString("full_name"), rs.getString("result"));
                result.computeIfAbsent(depositId, ignored -> new ArrayList<>()).add(record);
            }, centerId);
        return result;
    }

    public BigDecimal activeVolume(UUID depositId) {
        BigDecimal volume = jdbc.queryForObject(
            "select coalesce(sum(volume_liters), 0) from occupation where deposit_id = ? and end_at is null",
            BigDecimal.class, depositId);
        return volume == null ? BigDecimal.ZERO : volume;
    }

    private static java.time.Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
