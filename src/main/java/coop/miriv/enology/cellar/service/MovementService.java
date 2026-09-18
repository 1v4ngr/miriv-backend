package coop.miriv.enology.cellar.service;

import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.common.CodeGenerator;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.ConflictException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.common.exception.StaleBalanceException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
            throw new BusinessRuleException("Tipo de movimiento no soportado.");
        }
        if (!exit && (request.destinationDeposit() == null || request.destinationDeposit().isBlank())) {
            throw new BusinessRuleException("El depósito de destino es obligatorio.");
        }
        String sourceCode = normalize(request.sourceDeposit());
        String destinationCode = exit ? null : normalize(request.destinationDeposit());
        if (sourceCode.equals(destinationCode)) throw new BusinessRuleException("El origen y el destino deben ser distintos.");

        // F2-04 idempotency: a repeated confirmation with the same key must NOT duplicate
        // the movement. It returns the original result instead of erroring out (UI15).
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            MovementResponse replay = findByIdempotencyKey(request.idempotencyKey());
            if (replay != null) return replay;
        }

        List<DepositSlot> locked = jdbc.query(
            "select id, code, zone_id, status::text, useful_capacity_liters from deposit "
                + "where center_id = ? and active = true and (code = ? or code = ?) order by id for update",
            (rs, index) -> new DepositSlot(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("zone_id", UUID.class), rs.getString("status"), rs.getBigDecimal("useful_capacity_liters")), centerId, sourceCode, destinationCode);
        DepositSlot source = locked.stream().filter(item -> item.code().equals(sourceCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Depósito de origen no encontrado."));
        context.requireInZone("MOVEMENT_REGISTER", source.zoneId());
        DepositSlot destination = exit ? null : locked.stream().filter(item -> item.code().equals(destinationCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Depósito de destino no encontrado."));
        if (destination != null) {
            context.requireInZone("MOVEMENT_REGISTER", destination.zoneId());
        }
        if (destination != null && !destination.status().equals("AVAILABLE") && !destination.status().equals("OCCUPIED")) {
            throw new BusinessRuleException("DEPOSIT_NOT_AVAILABLE",
                "El depósito de destino no admite producto en su estado actual.");
        }
        if (!source.status().equals("OCCUPIED")) {
            throw new BusinessRuleException("El depósito de origen no está marcado como ocupado.");
        }
        OccupiedUnit sourceUnit = activeUnit(source.id());
        if (sourceUnit == null) throw new BusinessRuleException("El depósito de origen no tiene contenido activo.");
        BigDecimal withdrawn = request.volumeLiters().add(request.lossLiters());

        // F2-06 stale balance: if the wizard knew a specific balance when it computed the
        // withdrawal, ensure that balance is still the one in the database before we proceed.
        if (request.expectedSourceLiters() != null
                && sourceUnit.volume().compareTo(request.expectedSourceLiters()) != 0) {
            throw new StaleBalanceException(request.expectedSourceLiters(), sourceUnit.volume(), source.code());
        }

        if (withdrawn.compareTo(sourceUnit.volume()) > 0) {
            throw new BusinessRuleException("INSUFFICIENT_VOLUME",
                "La retirada supera el volumen disponible en el origen.");
        }
        Instant effectiveAt = request.effectiveDate().atTime(request.effectiveTime()).atZone(timezone).toInstant();
        if (effectiveAt.isBefore(sourceUnit.startedAt())) throw new BusinessRuleException("La fecha efectiva es anterior al inicio de la ocupación del origen.");
        OccupiedUnit destinationUnit = destination == null ? null : activeUnit(destination.id());
        if (destination != null && destination.status().equals("OCCUPIED") != (destinationUnit != null)) {
            throw new BusinessRuleException("El estado del depósito de destino y su ocupación son incoherentes.");
        }

        // F2-06 stale balance: destination can be checked too (volume 0 if it's empty).
        if (request.expectedDestinationLiters() != null && destination != null) {
            BigDecimal currentDestination = destinationUnit == null ? BigDecimal.ZERO : destinationUnit.volume();
            if (currentDestination.compareTo(request.expectedDestinationLiters()) != 0) {
                throw new StaleBalanceException(request.expectedDestinationLiters(), currentDestination, destination.code());
            }
        }

        if (destinationUnit != null && !request.authorizeMixture()) {
            throw new BusinessRuleException("MIXTURE_NOT_AUTHORIZED",
                "El destino está ocupado: necesitas autorizar la mezcla explícitamente.");
        }
        if (destinationUnit != null && request.authorizeMixture()) {
            context.requireInZone("MIXTURE_AUTHORIZE", destination.zoneId());
        }
        if (destinationUnit != null && effectiveAt.isBefore(destinationUnit.startedAt())) {
            throw new BusinessRuleException("La fecha efectiva es anterior al inicio de la ocupación del destino.");
        }
        BigDecimal destinationFinal = (destinationUnit == null ? BigDecimal.ZERO : destinationUnit.volume())
            .add(exit ? BigDecimal.ZERO : request.volumeLiters());
        if (destination != null && destinationFinal.compareTo(destination.capacity()) > 0) {
            throw new BusinessRuleException("CAPACITY_EXCEEDED",
                "El movimiento supera la capacidad útil del depósito de destino.");
        }
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            MovementResponse replay = findByIdempotencyKey(request.idempotencyKey());
            if (replay != null) return replay;
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
                    "Mezcla de " + sourceUnit.lotCode() + " y " + destinationUnit.lotCode());
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

    /**
     * Clears the active content of a deposit by recording a {@code LOSS} movement.
     * Used to undo a mistaken entry without keeping physical content in the deposit.
     * Audit trail is preserved through the movement (responsible + reason + effective time).
     */
    @Transactional
    public void clearOccupation(String depositCode, String reason, String responsible) {
        UUID centerId = context.centerId();
        DepositSlot source = jdbc.query(
                "select id, code, zone_id, status::text, useful_capacity_liters from deposit "
                    + "where center_id = ? and active = true and lower(code) = lower(?) for update",
                (rs, index) -> new DepositSlot(rs.getObject("id", UUID.class), rs.getString("code"),
                    rs.getObject("zone_id", UUID.class), rs.getString("status"), rs.getBigDecimal("useful_capacity_liters")),
                centerId, depositCode == null ? "" : depositCode.trim())
            .stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Depósito no encontrado."));
        context.requireInZone("CONTENT_CORRECT", source.zoneId());
        if (!source.status().equals("OCCUPIED") && !source.status().equals("PENDING_CLEANING")) {
            throw new BusinessRuleException("El depósito no tiene contenido activo que retirar.");
        }
        OccupiedUnit sourceUnit = activeUnit(source.id());
        if (sourceUnit == null) {
            throw new BusinessRuleException("El estado del depósito no coincide con ninguna ocupación activa.");
        }
        UUID responsiblePk = responsibleId(responsible, centerId);
        Instant effectiveAt = Instant.now();
        UUID movementId = UUID.randomUUID();
        String movementCode = code("MOV", LocalDate.now(timezone).getYear(), movementId);
        String trimmedReason = reason == null || reason.isBlank() ? "Corrección manual" : reason.trim();
        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason) "
                + "values (?, ?, 'LOSS'::movement_type, 'EXECUTED'::movement_status, ?, ?, ?)",
            movementId, movementCode, Timestamp.from(effectiveAt), responsiblePk, trimmedReason);
        jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, volume_liters) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), movementId, sourceUnit.contentId(), source.id(), sourceUnit.volume());
        jdbc.update("update occupation set end_at = ?, volume_liters = 0 where id = ?", Timestamp.from(effectiveAt), sourceUnit.occupationId());
        jdbc.update("update content_unit set volume_liters = 0, active = false where id = ?", sourceUnit.contentId());
        jdbc.update("update deposit set status = 'PENDING_CLEANING'::deposit_status, updated_at = now() where id = ?", source.id());
    }

    /** F2-04: return the original {@link MovementResponse} for a previously used idempotency key. */
    private MovementResponse findByIdempotencyKey(String idempotencyKey) {
        List<MovementResponse> existing = jdbc.query("""
            select m.code, m.type::text as type,
                   (select o.volume_liters from occupation o where o.content_unit_id = ml.source_content_unit_id
                      order by o.start_at desc limit 1) as source_final,
                   coalesce(dest.code, '') as destination_code,
                   (select o.volume_liters from occupation o where o.content_unit_id = ml.destination_content_unit_id
                      order by o.start_at desc limit 1) as destination_final
              from movement m join movement_line ml on ml.movement_id = m.id
              left join content_unit dest on dest.id = ml.destination_content_unit_id
             where m.idempotency_key = ?
             order by ml.id
             limit 1
            """,
            (rs, index) -> new MovementResponse(
                rs.getString("code"),
                "MIX".equals(rs.getString("type")),
                rs.getBigDecimal("source_final"),
                rs.getString("destination_code"),
                rs.getBigDecimal("destination_final")),
            idempotencyKey);
        return existing.isEmpty() ? null : existing.getFirst();
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
                + "and (lower(username) = lower(?) or lower(email) = lower(?))",
            (rs, index) -> rs.getObject(1, UUID.class), centerId, value.trim(), value.trim());
        if (ids.isEmpty()) throw new NotFoundException("Responsable no encontrado en el centro actual: " + value);
        return ids.getFirst();
    }

    private String code(String prefix, int year, UUID id) {
        return prefix + "-" + year + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private record DepositSlot(UUID id, String code, UUID zoneId, String status, BigDecimal capacity) {}
    private record OccupiedUnit(UUID occupationId, UUID contentId, String contentCode, String lotCode,
                                BigDecimal volume, Instant startedAt) {}
}
