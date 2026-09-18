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
                   o.start_at, o.end_at, o.volume_liters,
                   cat.name as category,
                   coalesce(fs.confirmed_status, fs.estimated_status, 'NOT_EVALUATED') as fermentation_status,
                   coalesce(fs2.confirmed_status, fs2.estimated_status, 'NOT_EVALUATED') as malolactic_status
              from occupation o
              join deposit d on d.id = o.deposit_id
              join content_unit c on c.id = o.content_unit_id
              join lot l on l.id = c.lot_id
              left join internal_category cat on cat.id = c.category_id
              left join fermentation_state fs on fs.content_unit_id = c.id and fs.process = 'ALCOHOLIC'
              left join fermentation_state fs2 on fs2.content_unit_id = c.id and fs2.process = 'MALOLACTIC'
             where d.center_id = ?
             order by o.start_at desc
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                UUID depositId = rs.getObject("deposit_id", UUID.class);
                // F2-03: category and fermentation status are now codes (or null) on the wire.
                OccupationResponse occupation = new OccupationResponse(
                    rs.getString("content_code"), rs.getString("lot_code"),
                    rs.getTimestamp("start_at").toInstant(), instantOrNull(rs.getTimestamp("end_at")),
                    rs.getBigDecimal("volume_liters"), rs.getString("category"),
                    rs.getString("fermentation_status"), rs.getString("malolactic_status"));
                result.computeIfAbsent(depositId, ignored -> new ArrayList<>()).add(occupation);
            }, centerId);
        return result;
    }

    /** F2-07: read only the occupations for a single deposit (replaces the per-center scan). */
    public List<OccupationResponse> occupationsByDeposit(UUID depositId) {
        List<OccupationResponse> result = new ArrayList<>();
        jdbc.query("""
            select c.code as content_code, l.code as lot_code,
                   o.start_at, o.end_at, o.volume_liters,
                   cat.name as category,
                   coalesce(fs.confirmed_status, fs.estimated_status, 'NOT_EVALUATED') as fermentation_status,
                   coalesce(fs2.confirmed_status, fs2.estimated_status, 'NOT_EVALUATED') as malolactic_status
              from occupation o
              join content_unit c on c.id = o.content_unit_id
              join lot l on l.id = c.lot_id
              left join internal_category cat on cat.id = c.category_id
              left join fermentation_state fs on fs.content_unit_id = c.id and fs.process = 'ALCOHOLIC'
              left join fermentation_state fs2 on fs2.content_unit_id = c.id and fs2.process = 'MALOLACTIC'
             where o.deposit_id = ?
             order by o.start_at desc
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.add(new OccupationResponse(
                rs.getString("content_code"), rs.getString("lot_code"),
                rs.getTimestamp("start_at").toInstant(), instantOrNull(rs.getTimestamp("end_at")),
                rs.getBigDecimal("volume_liters"), rs.getString("category"),
                rs.getString("fermentation_status"), rs.getString("malolactic_status"))), depositId);
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
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                UUID depositId = rs.getObject("deposit_id", UUID.class);
                CleaningRecordResponse record = new CleaningRecordResponse(
                    rs.getTimestamp("performed_at").toInstant(), rs.getString("action"),
                    rs.getString("full_name"), rs.getString("result"));
                result.computeIfAbsent(depositId, ignored -> new ArrayList<>()).add(record);
            }, centerId);
        return result;
    }

    /** F2-07: read only the cleaning records for a single deposit. */
    public List<CleaningRecordResponse> cleaningByDeposit(UUID depositId) {
        List<CleaningRecordResponse> result = new ArrayList<>();
        jdbc.query("""
            select r.performed_at, r.action, u.full_name, r.result
              from deposit_cleaning_record r
              left join app_user u on u.id = r.responsible_id
             where r.deposit_id = ?
             order by r.performed_at desc
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.add(new CleaningRecordResponse(
                rs.getTimestamp("performed_at").toInstant(), rs.getString("action"),
                rs.getString("full_name"), rs.getString("result"))), depositId);
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
