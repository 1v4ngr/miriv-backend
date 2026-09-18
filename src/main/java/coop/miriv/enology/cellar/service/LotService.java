package coop.miriv.enology.cellar.service;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.LotEntryRequest;
import coop.miriv.enology.cellar.dto.LotRequest;
import coop.miriv.enology.cellar.dto.LotResponse;
import coop.miriv.enology.cellar.dto.LineageEventResponse;
import coop.miriv.enology.cellar.dto.UpdateLotRequest;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ZoneId timezone;

    public LotService(JdbcTemplate jdbc, CurrentUserContext context,
                       @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public List<LotResponse> list() {
        UUID centerId = context.centerId();
        List<LotRow> rows = jdbc.query(LOT_SELECT + " where l.center_id = ? order by l.created_at desc",
            (rs, index) -> row(rs), centerId);
        return enrich(rows);
    }

    @Transactional(readOnly = true)
    public LotResponse get(String code) {
        return enrich(List.of(find(code, context.centerId()))).getFirst();
    }

    @Transactional(readOnly = true)
    public List<LineageEventResponse> genealogy(String code) {
        LotRow lot = find(code, context.centerId());
        return jdbc.query("""
            select parent.code as origin, child.code as destination, m.effective_at, m.code as movement,
                   lineage.contributed_liters as volume_liters, m.type::text as note
              from content_unit_lineage lineage
              join content_unit child on child.id = lineage.content_unit_id
              join content_unit parent on parent.id = lineage.parent_content_unit_id
              join movement m on m.id = lineage.movement_id
             where child.lot_id = ? or parent.lot_id = ?
            union all
            select 'Entry ' || l.code as origin, child.code as destination, m.effective_at,
                   m.code as movement, ml.volume_liters, 'ENTRY' as note
              from movement_line ml
              join movement m on m.id = ml.movement_id
              join content_unit child on child.id = ml.destination_content_unit_id
              join lot l on l.id = child.lot_id
             where child.lot_id = ? and ml.source_content_unit_id is null
             order by effective_at
            """, (rs, index) -> new LineageEventResponse(rs.getString("origin"),
            rs.getString("destination"), rs.getTimestamp("effective_at").toInstant(),
            rs.getString("movement"), rs.getBigDecimal("volume_liters"), rs.getString("note")),
            lot.id(), lot.id(), lot.id());
    }

    @Transactional
    public LotResponse create(CreateLotRequest request) {
        UUID centerId = context.centerId();
        LotRequest lot = request.lot();
        String code = normalize(lot.code());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from lot where code = ?)", Boolean.class, code))) {
            throw new ConflictException("A lot with this code already exists.");
        }
        UUID categoryId = catalogId("internal_category", lot.category());
        UUID destinationId = catalogId("destination", lot.destination());
        UUID responsibleId = responsibleId(lot.responsible(), centerId);
        UUID lotId = UUID.randomUUID();
        jdbc.update("insert into lot(id, code, center_id, campaign, category_id, destination_id, entry_date, responsible_id, origin_summary) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            lotId, code, centerId, lot.campaign(), categoryId, destinationId, lot.entryDate(), responsibleId, blankToNull(lot.origin()));
        saveVariety(lotId, lot.variety());
        if (request.entry() != null) {
            createEntry(lotId, code, lot.campaign(), categoryId, responsibleId, centerId, request.entry());
        }
        return get(code);
    }

    @Transactional
    public LotResponse update(String code, UpdateLotRequest request) {
        UUID centerId = context.centerId();
        LotRow lot = find(code, centerId);
        UUID destinationId = catalogId("destination", request.destination());
        UUID responsibleId = responsibleId(request.responsible(), centerId);
        jdbc.update("update lot set destination_id = ?, responsible_id = ?, origin_summary = ? where id = ?",
            destinationId, responsibleId, blankToNull(request.origin()), lot.id());
        jdbc.update("delete from lot_variety where lot_id = ?", lot.id());
        saveVariety(lot.id(), request.variety());
        return get(code);
    }

    private void createEntry(UUID lotId, String lotCode, int campaign, UUID categoryId,
                             UUID responsibleId, UUID centerId, LotEntryRequest entry) {
        List<DepositSlot> slots = jdbc.query("select id, status::text, useful_capacity_liters from deposit where center_id = ? and code = ? and active = true for update",
            (rs, index) -> new DepositSlot(rs.getObject("id", UUID.class), rs.getString("status"), rs.getBigDecimal("useful_capacity_liters")),
            centerId, normalize(entry.depositCode()));
        if (slots.isEmpty()) throw new NotFoundException("Deposit not found in the current center.");
        DepositSlot slot = slots.getFirst();
        if (!slot.status().equals("AVAILABLE")) throw new BusinessRuleException("The deposit is not available for entry.");
        if (entry.volumeLiters().compareTo(slot.capacity()) > 0) throw new BusinessRuleException("Entry volume exceeds useful capacity.");
        Instant effectiveAt = entry.effectiveDate().atStartOfDay(timezone).toInstant();
        UUID movementId = UUID.randomUUID();
        String movementCode = "MOV-" + campaign + "-" + movementId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason) values (?, ?, 'ENTRY'::movement_type, 'EXECUTED'::movement_status, ?, ?, ?)",
            movementId, movementCode, Timestamp.from(effectiveAt), responsibleId, "Initial lot entry " + lotCode);
        UUID contentId = UUID.randomUUID();
        String contentCode = "C-" + campaign + "-" + contentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("insert into content_unit(id, code, lot_id, category_id, volume_liters) values (?, ?, ?, ?, ?)",
            contentId, contentCode, lotId, categoryId, entry.volumeLiters());
        jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, ?)",
            UUID.randomUUID(), contentId, slot.id(), Timestamp.from(effectiveAt), entry.volumeLiters());
        jdbc.update("insert into movement_line(id, movement_id, destination_content_unit_id, destination_deposit_id, volume_liters) values (?, ?, ?, ?, ?)",
            UUID.randomUUID(), movementId, contentId, slot.id(), entry.volumeLiters());
        jdbc.update("update deposit set status = 'OCCUPIED'::deposit_status, updated_at = ? where id = ?", Timestamp.from(Instant.now()), slot.id());
    }

    private UUID catalogId(String table, String value) {
        // The table name is selected by callers from two fixed, internal catalog names.
        List<UUID> ids = jdbc.query("select id from " + table + " where active = true and (lower(name) = lower(?) or lower(code) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Catalog value not found: " + value);
        return ids.getFirst();
    }

    private void saveVariety(UUID lotId, String value) {
        if (value == null || value.isBlank()) return;
        List<UUID> ids = jdbc.query("select id from variety where active = true and (lower(name) = lower(?) or lower(code) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), value.trim(), value.trim());
        if (!ids.isEmpty()) jdbc.update("insert into lot_variety(id, lot_id, variety_id) values (?, ?, ?)", UUID.randomUUID(), lotId, ids.getFirst());
    }

    private UUID responsibleId(String value, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from app_user where center_id = ? and active = true and (lower(full_name) = lower(?) or lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsible user not found in the current center.");
        return ids.getFirst();
    }

    private LotRow find(String code, UUID centerId) {
        List<LotRow> rows = jdbc.query(LOT_SELECT + " where l.center_id = ? and l.code = ?",
            (rs, index) -> row(rs), centerId, normalize(code));
        if (rows.isEmpty()) throw new NotFoundException("Lot not found.");
        return rows.getFirst();
    }

    private List<LotResponse> enrich(List<LotRow> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, List<String>> content = new HashMap<>();
        Map<UUID, List<String>> varieties = new HashMap<>();
        for (LotRow row : rows) {
            content.put(row.id(), jdbc.query("select code from content_unit where lot_id = ? order by created_at",
                (rs, index) -> rs.getString(1), row.id()));
            varieties.put(row.id(), jdbc.query("select v.name from lot_variety lv join variety v on v.id = lv.variety_id where lv.lot_id = ? order by v.name",
                (rs, index) -> rs.getString(1), row.id()));
        }
        List<LotResponse> result = new ArrayList<>();
        for (LotRow row : rows) result.add(new LotResponse(row.code(), row.campaign(),
            row.category() == null ? "Unclassified" : row.category(),
            row.destination() == null ? "Pending" : row.destination(),
            row.responsible(), row.entryDate(), row.origin() == null ? "" : row.origin(),
            String.join(", ", varieties.get(row.id())), row.archived(), content.get(row.id())));
        return result;
    }

    private LotRow row(ResultSet rs) throws SQLException {
        return new LotRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("campaign"),
            rs.getString("category"), rs.getString("destination"), rs.getString("responsible"),
            rs.getDate("entry_date").toLocalDate(), rs.getString("origin_summary"), rs.getBoolean("archived"));
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static final String LOT_SELECT = "select l.id, l.code, l.campaign, c.name as category, d.name as destination, "
        + "u.full_name as responsible, l.entry_date, l.origin_summary, l.archived from lot l "
        + "join app_user u on u.id = l.responsible_id "
        + "left join internal_category c on c.id = l.category_id "
        + "left join destination d on d.id = l.destination_id";

    private record LotRow(UUID id, String code, int campaign, String category, String destination,
                          String responsible, java.time.LocalDate entryDate, String origin, boolean archived) {}
    private record DepositSlot(UUID id, String status, java.math.BigDecimal capacity) {}
}
