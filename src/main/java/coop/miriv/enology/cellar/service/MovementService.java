package coop.miriv.enology.cellar.service;

import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MovementService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final ZoneId timezone;

    public MovementService(JdbcTemplate jdbc, CurrentUserContext context,
                           @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.timezone = ZoneId.of(timezone);
    }

    @Transactional
    public MovementResponse register(MovementRequest request) {
        UUID centerId = context.centerId();
        boolean exit = request.type().equalsIgnoreCase("Salida");
        if (!exit && !request.type().equalsIgnoreCase("Trasiego") && !request.type().equalsIgnoreCase("Trasvase")) {
            throw new BusinessRuleException("Unsupported movement type.");
        }
        if (!exit && (request.destinationDeposit() == null || request.destinationDeposit().isBlank())) {
            throw new BusinessRuleException("Destination deposit is required.");
        }
        String sourceCode = normalize(request.sourceDeposit());
        String destinationCode = exit ? null : normalize(request.destinationDeposit());
        if (sourceCode.equals(destinationCode)) throw new BusinessRuleException("Source and destination must differ.");
        List<DepositSlot> locked = jdbc.query(
            "select id, code, status::text, useful_capacity_liters from deposit "
                + "where center_id = ? and active = true and (code = ? or code = ?) order by id for update",
            (rs, index) -> new DepositSlot(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("status"), rs.getBigDecimal("useful_capacity_liters")), centerId, sourceCode, destinationCode);
        DepositSlot source = locked.stream().filter(item -> item.code().equals(sourceCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Source deposit not found."));
        DepositSlot destination = exit ? null : locked.stream().filter(item -> item.code().equals(destinationCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Destination deposit not found."));
        if (destination != null && !destination.status().equals("AVAILABLE") && !destination.status().equals("OCCUPIED")) {
            throw new BusinessRuleException("Destination deposit cannot receive product in its current state.");
        }
        if (!source.status().equals("OCCUPIED")) {
            throw new BusinessRuleException("Source deposit is not marked as occupied.");
        }
        OccupiedUnit sourceUnit = activeUnit(source.id());
        if (sourceUnit == null) throw new BusinessRuleException("Source deposit has no active content.");
        BigDecimal withdrawn = request.volumeLiters().add(request.lossLiters());
        if (withdrawn.compareTo(sourceUnit.volume()) > 0) {
            throw new BusinessRuleException("Withdrawal exceeds the available source volume.");
        }
        Instant effectiveAt = request.effectiveDate().atTime(request.effectiveTime()).atZone(timezone).toInstant();
        if (effectiveAt.isBefore(sourceUnit.startedAt())) throw new BusinessRuleException("Effective time precedes the source occupation.");
        OccupiedUnit destinationUnit = destination == null ? null : activeUnit(destination.id());
        if (destination != null && destination.status().equals("OCCUPIED") != (destinationUnit != null)) {
            throw new BusinessRuleException("Destination status and occupation are inconsistent.");
        }
        if (destinationUnit != null && !request.authorizeMixture()) {
            throw new BusinessRuleException("An occupied destination requires explicit mixture authorization.");
        }
        if (destinationUnit != null && effectiveAt.isBefore(destinationUnit.startedAt())) {
            throw new BusinessRuleException("Effective time precedes the destination occupation.");
        }
        BigDecimal destinationFinal = (destinationUnit == null ? BigDecimal.ZERO : destinationUnit.volume())
            .add(exit ? BigDecimal.ZERO : request.volumeLiters());
        if (destination != null && destinationFinal.compareTo(destination.capacity()) > 0) {
            throw new BusinessRuleException("Movement exceeds destination useful capacity.");
        }
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            List<String> duplicate = jdbc.query("select code from movement where idempotency_key = ?",
                (rs, index) -> rs.getString(1), request.idempotencyKey());
            if (!duplicate.isEmpty()) throw new BusinessRuleException("This movement was already registered: " + duplicate.getFirst());
        }
        UUID responsibleId = responsibleId(request.responsible(), centerId);
        UUID movementId = UUID.randomUUID();
        String movementCode = code("MOV", request.effectiveDate().getYear(), movementId);
        boolean mixture = destinationUnit != null;
        String movementType = exit ? "EXIT" : mixture ? "MIX" : withdrawn.compareTo(sourceUnit.volume()) == 0 ? "TRANSFER_FULL" : "TRANSFER_PARTIAL";
        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason, idempotency_key) "
                + "values (?, ?, cast(? as movement_type), 'EXECUTED'::movement_status, ?, ?, ?, ?)",
            movementId, movementCode, movementType, Timestamp.from(effectiveAt), responsibleId, request.reason().trim(),
            request.idempotencyKey() == null || request.idempotencyKey().isBlank() ? null : request.idempotencyKey());

        BigDecimal sourceFinal = sourceUnit.volume().subtract(withdrawn);
        if (sourceFinal.signum() == 0) {
            jdbc.update("update occupation set end_at = ?, volume_liters = 0 where id = ?", Timestamp.from(effectiveAt), sourceUnit.occupationId());
            jdbc.update("update content_unit set volume_liters = 0, active = false where id = ?", sourceUnit.contentId());
            jdbc.update("update deposit set status = 'PENDING_CLEANING'::deposit_status, updated_at = now() where id = ?", source.id());
        } else {
            jdbc.update("update occupation set volume_liters = ? where id = ?", sourceFinal, sourceUnit.occupationId());
            jdbc.update("update content_unit set volume_liters = ? where id = ?", sourceFinal, sourceUnit.contentId());
        }

        UUID resultContentId = null;
        String resultContentCode = "";
        if (destination != null) {
            if (mixture) {
                UUID newLotId = UUID.randomUUID();
                String newLotCode = code("MIX", request.effectiveDate().getYear(), newLotId);
                jdbc.update("insert into lot(id, code, center_id, campaign, entry_date, responsible_id, origin_summary) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", newLotId, newLotCode, centerId,
                    request.effectiveDate().getYear(), request.effectiveDate(), responsibleId,
                    "Mixture of " + sourceUnit.lotCode() + " and " + destinationUnit.lotCode());
                jdbc.update("update occupation set end_at = ? where id = ?", Timestamp.from(effectiveAt), destinationUnit.occupationId());
                jdbc.update("update content_unit set active = false where id = ?", destinationUnit.contentId());
                resultContentId = UUID.randomUUID();
                resultContentCode = code("C", request.effectiveDate().getYear(), resultContentId);
                jdbc.update("insert into content_unit(id, code, lot_id, volume_liters) values (?, ?, ?, ?)",
                    resultContentId, resultContentCode, newLotId, destinationFinal);
                lineage(resultContentId, sourceUnit.contentId(), movementId, request.volumeLiters());
                lineage(resultContentId, destinationUnit.contentId(), movementId, destinationUnit.volume());
            } else if (sourceFinal.signum() == 0) {
                resultContentId = sourceUnit.contentId();
                resultContentCode = sourceUnit.contentCode();
                jdbc.update("update content_unit set active = true, volume_liters = ? where id = ?",
                    request.volumeLiters(), resultContentId);
            } else {
                resultContentId = UUID.randomUUID();
                resultContentCode = code("C", request.effectiveDate().getYear(), resultContentId);
                jdbc.update("insert into content_unit(id, code, lot_id, category_id, color_id, volume_liters) "
                        + "select ?, ?, lot_id, category_id, color_id, ? from content_unit where id = ?",
                    resultContentId, resultContentCode, request.volumeLiters(), sourceUnit.contentId());
                lineage(resultContentId, sourceUnit.contentId(), movementId, request.volumeLiters());
            }
            jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), resultContentId, destination.id(), Timestamp.from(effectiveAt), destinationFinal);
            jdbc.update("update deposit set status = 'OCCUPIED'::deposit_status, updated_at = now() where id = ?", destination.id());
        }
        jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, "
                + "destination_content_unit_id, destination_deposit_id, volume_liters, loss_liters) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), movementId, sourceUnit.contentId(),
            source.id(), resultContentId, destination == null ? null : destination.id(), request.volumeLiters(), request.lossLiters());
        if (mixture) {
            jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, "
                    + "destination_content_unit_id, destination_deposit_id, volume_liters) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), movementId, destinationUnit.contentId(), destination.id(), resultContentId,
                destination.id(), destinationUnit.volume());
        }
        return new MovementResponse(movementCode, mixture, sourceFinal, resultContentCode, destinationFinal);
    }

    private OccupiedUnit activeUnit(UUID depositId) {
        List<OccupiedUnit> units = jdbc.query("select o.id as occupation_id, o.content_unit_id, cu.code as content_code, "
                + "l.code as lot_code, o.volume_liters, o.start_at from occupation o "
                + "join content_unit cu on cu.id = o.content_unit_id join lot l on l.id = cu.lot_id "
                + "where o.deposit_id = ? and o.end_at is null for update of o",
            (rs, index) -> occupied(rs), depositId);
        return units.isEmpty() ? null : units.getFirst();
    }

    private OccupiedUnit occupied(ResultSet rs) throws SQLException {
        return new OccupiedUnit(rs.getObject("occupation_id", UUID.class), rs.getObject("content_unit_id", UUID.class),
            rs.getString("content_code"), rs.getString("lot_code"), rs.getBigDecimal("volume_liters"),
            rs.getTimestamp("start_at").toInstant());
    }

    private void lineage(UUID child, UUID parent, UUID movement, BigDecimal contributed) {
        jdbc.update("insert into content_unit_lineage(id, content_unit_id, parent_content_unit_id, movement_id, contributed_liters) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), child, parent, movement, contributed);
    }

    private UUID responsibleId(String value, UUID centerId) {
        List<UUID> ids = jdbc.query("select id from app_user where center_id = ? and active = true "
                + "and (lower(full_name) = lower(?) or lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsible user not found in the current center.");
        return ids.getFirst();
    }

    private String code(String prefix, int year, UUID id) {
        return prefix + "-" + year + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private record DepositSlot(UUID id, String code, String status, BigDecimal capacity) {}
    private record OccupiedUnit(UUID occupationId, UUID contentId, String contentCode, String lotCode,
                                BigDecimal volume, Instant startedAt) {}
}
