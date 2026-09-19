package coop.miriv.enology.cellar.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.cellar.dto.CancelMovementRequest;
import coop.miriv.enology.cellar.dto.MovementDetailResponse;
import coop.miriv.enology.cellar.dto.MovementLineResponse;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.cellar.dto.MovementSummaryResponse;
import coop.miriv.enology.common.CodeGenerator;
import coop.miriv.enology.common.dto.PageResponse;
import coop.miriv.enology.common.exception.BusinessRuleException;
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
    private final CodeGenerator codes;
    private final AuditService audit;

    public MovementService(JdbcTemplate jdbc, CurrentUserContext context,
                           @Value("${app.timezone}") String timezone, CodeGenerator codes, AuditService audit) {
        this.jdbc = jdbc;
        this.context = context;
        this.timezone = ZoneId.of(timezone);
        this.codes = codes;
        this.audit = audit;
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    @Transactional
    public MovementResponse register(MovementRequest request) {
        UUID centerId = context.centerId();
        validateRequestType(request);
        String sourceCode = normalize(request.sourceDeposit());
        String destinationCode = request.type().equalsIgnoreCase("Salida") ? null : normalize(request.destinationDeposit());
        if (sourceCode.equals(destinationCode)) throw new BusinessRuleException("El origen y el destino deben ser distintos.");

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            MovementResponse replay = findByIdempotencyKey(request.idempotencyKey());
            if (replay != null) return replay;
        }

        if (request.planned()) {
            return registerPlanned(request, centerId, sourceCode, destinationCode);
        }
        return execute(request, null, centerId);
    }

    /** F4-01: execute a previously PLANNED movement against the current data. */
    @Transactional
    public MovementResponse execute(String code) {
        MovementRequest planned = loadPlanned(code);
        return execute(planned, code, context.centerId());
    }

    /** F4-01: cancel a PLANNED movement. EXECUTED ones return 422 — issue a correction instead. */
    @Transactional
    public void cancel(String code, CancelMovementRequest request) {
        UUID centerId = context.centerId();
        MovementRow row = loadMovement(code, centerId);
        if (!"PLANNED".equals(row.status())) {
            throw new BusinessRuleException("Un movimiento ejecutado no se anula; registra un movimiento de corrección.");
        }
        jdbc.update("update movement set status = 'CANCELLED'::movement_status, cancelled_reason = ? where id = ?",
            request.reason().trim(), row.id());
        audit.record("movement", row.id(), "MOVEMENT_CANCELLED", "Previsto cancelado: " + request.reason().trim());
    }

    /** F4-01: paged history of movements visible to the current user. */
    public PageResponse<MovementSummaryResponse> list(LocalDate from, LocalDate to,
                                                      String depositCode, String lotCode, String type,
                                                      String status, String authorUsername, String q,
                                                      int page, int size) {
        UUID centerId = context.centerId();
        CurrentUserContext.ZoneFilter zoneFilter = context.readZoneFilter("src");
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder("where m.center_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(centerId);
        if (!zoneFilter.allZones()) where.append(zoneFilter.sql());
        if (from != null) { where.append(" and m.effective_at >= ?"); args.add(Timestamp.from(from.atStartOfDay(timezone).toInstant())); }
        if (to != null) { where.append(" and m.effective_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay(timezone).toInstant())); }
        if (depositCode != null && !depositCode.isBlank()) {
            where.append(" and (exists (select 1 from movement_line ml2 join deposit d on d.id = ml2.source_deposit_id where ml2.movement_id = m.id and lower(d.code) = lower(?)) "
                + " or exists (select 1 from movement_line ml2 join deposit d on d.id = ml2.destination_deposit_id where ml2.movement_id = m.id and lower(d.code) = lower(?)))");
            args.add(depositCode.trim()); args.add(depositCode.trim());
        }
        if (lotCode != null && !lotCode.isBlank()) {
            where.append(" and exists (select 1 from movement_line ml2 join content_unit cu on cu.id = ml2.source_content_unit_id join lot l on l.id = cu.lot_id "
                + "where ml2.movement_id = m.id and lower(l.code) = lower(?))");
            args.add(lotCode.trim());
        }
        if (type != null && !type.isBlank()) { where.append(" and m.type::text = ?"); args.add(type.trim()); }
        if (status != null && !status.isBlank()) { where.append(" and m.status::text = ?"); args.add(status.trim()); }
        if (authorUsername != null && !authorUsername.isBlank()) {
            where.append(" and exists (select 1 from app_user u where u.id = m.responsible_id and lower(u.username) = lower(?))");
            args.add(authorUsername.trim());
        }
        if (q != null && !q.isBlank()) { where.append(" and m.code ilike ?"); args.add("%" + q.trim() + "%"); }

        Long total = jdbc.queryForObject("select count(*) from movement m " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize); pageArgs.add(offset);
        List<MovementSummaryResponse> items = jdbc.query("""
            select m.code, m.type::text as type, m.status::text as status, m.effective_at, m.registered_at,
                   responsible.username as responsible, registered.username as registered_by,
                   (select coalesce(string_agg(distinct sd.code, ',' order by sd.code), '') from movement_line ml2
                       join deposit sd on sd.id = ml2.source_deposit_id where ml2.movement_id = m.id) as src_codes,
                   (select coalesce(string_agg(distinct dd.code, ',' order by dd.code), '') from movement_line ml2
                       join deposit dd on dd.id = ml2.destination_deposit_id where ml2.movement_id = m.id) as dst_codes,
                   (select coalesce(string_agg(distinct l.code, ',' order by l.code), '') from movement_line ml2
                       join content_unit cu on cu.id = ml2.source_content_unit_id
                       join lot l on l.id = cu.lot_id where ml2.movement_id = m.id) as lot_codes,
                   (select coalesce(sum(volume_liters), 0) from movement_line where movement_id = m.id) as vol
              from movement m
              left join app_user responsible on responsible.id = m.responsible_id
              left join app_user registered on registered.id = m.registered_by_id
            """ + where + " order by m.effective_at desc, m.code limit ? offset ?",
            (rs, index) -> new MovementSummaryResponse(
                rs.getString("code"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("registered_at").toInstant(),
                split(rs.getString("src_codes")),
                split(rs.getString("dst_codes")),
                split(rs.getString("lot_codes")),
                rs.getBigDecimal("vol"),
                rs.getString("responsible"),
                rs.getString("registered_by")),
            pageArgs.toArray());
        return PageResponse.of(items, totalCount, safePage, safeSize);
    }

    /** F4-01: full detail with per-line before/after balances. */
    public MovementDetailResponse get(String code) {
        MovementRow row = loadMovement(code, context.centerId());
        List<MovementLineResponse> lines = jdbc.query("""
            select ml.id, src.code as src_code, src_cu.code as src_content,
                   dst.code as dst_code, dst_cu.code as dst_content,
                   ml.volume_liters, ml.loss_liters,
                   ml.source_before_liters, ml.source_after_liters,
                   ml.destination_before_liters, ml.destination_after_liters
              from movement_line ml
              left join deposit src on src.id = ml.source_deposit_id
              left join content_unit src_cu on src_cu.id = ml.source_content_unit_id
              left join deposit dst on dst.id = ml.destination_deposit_id
              left join content_unit dst_cu on dst_cu.id = ml.destination_content_unit_id
             where ml.movement_id = ?
             order by ml.id
            """,
            (rs, index) -> new MovementLineResponse(
                rs.getString("src_code"), rs.getString("src_content"),
                rs.getString("dst_code"), rs.getString("dst_content"),
                rs.getBigDecimal("volume_liters"), rs.getBigDecimal("loss_liters"),
                rs.getBigDecimal("source_before_liters"), rs.getBigDecimal("source_after_liters"),
                rs.getBigDecimal("destination_before_liters"), rs.getBigDecimal("destination_after_liters")),
            row.id());
        return new MovementDetailResponse(row.code(), row.type(), row.status(), row.effectiveAt(), row.registeredAt(),
            row.responsible(), row.registeredBy(), row.reason(), row.cancelledReason(), lines);
    }

    // ---------------------------------------------------------------------------------------
    // Registration paths (PLANNED vs EXECUTED)
    // ---------------------------------------------------------------------------------------

    private MovementResponse registerPlanned(MovementRequest request, UUID centerId,
                                             String sourceCode, String destinationCode) {
        // Permission gate happens in the controller layer (F1C-03). Here we only validate data.
        List<DepositSlot> locked = jdbc.query(
            "select id, code, zone_id, status::text, useful_capacity_liters from deposit "
                + "where center_id = ? and active = true and (code = ? or code = ?) order by id for update",
            (rs, index) -> new DepositSlot(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getObject("zone_id", UUID.class), rs.getString("status"), rs.getBigDecimal("useful_capacity_liters")),
            centerId, sourceCode, destinationCode);
        DepositSlot source = locked.stream().filter(item -> item.code().equals(sourceCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Depósito de origen no encontrado."));
        DepositSlot destination = destinationCode == null ? null : locked.stream().filter(item -> item.code().equals(destinationCode)).findFirst()
            .orElseThrow(() -> new NotFoundException("Depósito de destino no encontrado."));

        UUID responsibleId = responsibleId(request.responsible(), centerId);
        Instant effectiveAt = request.effectiveDate().atTime(request.effectiveTime()).atZone(timezone).toInstant();
        String movementCode = codes.next("MOV", request.effectiveDate().getYear());
        UUID movementId = UUID.randomUUID();

        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason, "
                + "idempotency_key, registered_by_id, planned_source_deposit, planned_destination_deposit, "
                + "planned_volume_liters, planned_loss_liters, planned_authorize_mixture) "
                + "values (?, ?, cast(? as movement_type), 'PLANNED'::movement_status, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            movementId, movementCode, plannedType(request), Timestamp.from(effectiveAt), responsibleId,
            request.reason().trim(),
            request.idempotencyKey() == null || request.idempotencyKey().isBlank() ? null : request.idempotencyKey(),
            context.userId(), sourceCode, destinationCode, request.volumeLiters(), request.lossLiters(), request.authorizeMixture());

        audit.record("movement", movementId, "MOVEMENT_PLANNED",
            "Previsto " + source.code() + " → " + (destination == null ? "salida" : destination.code())
                + " · " + request.volumeLiters() + " L");
        // F4-01: planned movements carry no line balances. The detail screen will render "No registrado".
        return new MovementResponse(movementCode, false, null, null, null, "PLANNED", effectiveAt);
    }

    /**
     * Execute a movement request against the current data, optionally upgrading a previously
     * PLANNED row. Snapshots {@code source_before_liters / source_after_liters / *_before / *_after}
     * on every line so the detail page can render them without rehydrating historical balances.
     */
    private MovementResponse execute(MovementRequest request, String plannedCode, UUID centerId) {
        boolean exit = request.type().equalsIgnoreCase("Salida");
        String sourceCode = normalize(request.sourceDeposit());
        String destinationCode = exit ? null : normalize(request.destinationDeposit());

        // F2-04 idempotency: a repeated confirmation with the same key must NOT duplicate the movement.
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
        BigDecimal sourceBefore = sourceUnit.volume();
        BigDecimal withdrawn = request.volumeLiters().add(request.lossLiters());

        // F2-06 stale balance.
        if (request.expectedSourceLiters() != null
                && sourceBefore.compareTo(request.expectedSourceLiters()) != 0) {
            throw new StaleBalanceException(request.expectedSourceLiters(), sourceBefore, source.code());
        }
        if (withdrawn.compareTo(sourceBefore) > 0) {
            throw new BusinessRuleException("INSUFFICIENT_VOLUME",
                "La retirada supera el volumen disponible en el origen.");
        }
        Instant effectiveAt = request.effectiveDate().atTime(request.effectiveTime()).atZone(timezone).toInstant();
        if (effectiveAt.isBefore(sourceUnit.startedAt())) throw new BusinessRuleException("La fecha efectiva es anterior al inicio de la ocupación del origen.");
        OccupiedUnit destinationUnit = destination == null ? null : activeUnit(destination.id());
        if (destination != null && destination.status().equals("OCCUPIED") != (destinationUnit != null)) {
            throw new BusinessRuleException("El estado del depósito de destino y su ocupación son incoherentes.");
        }
        BigDecimal destinationBefore = destinationUnit == null ? BigDecimal.ZERO : destinationUnit.volume();
        if (request.expectedDestinationLiters() != null && destination != null
                && destinationBefore.compareTo(request.expectedDestinationLiters()) != 0) {
            throw new StaleBalanceException(request.expectedDestinationLiters(), destinationBefore, destination.code());
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
        BigDecimal destinationFinal = destinationBefore.add(exit ? BigDecimal.ZERO : request.volumeLiters());
        if (destination != null && destinationFinal.compareTo(destination.capacity()) > 0) {
            throw new BusinessRuleException("CAPACITY_EXCEEDED",
                "El movimiento supera la capacidad útil del depósito de destino.");
        }

        UUID responsibleId = responsibleId(request.responsible(), centerId);
        UUID movementId;
        String movementCode;
        if (plannedCode != null) {
            MovementRow existing = loadMovement(plannedCode, centerId);
            movementId = existing.id();
            movementCode = existing.code();
            jdbc.update("update movement set status = 'EXECUTED'::movement_status, effective_at = ?, responsible_id = ?, "
                + "reason = ?, planned_request = null, registered_by_id = ?, registered_at = now() where id = ?",
                Timestamp.from(effectiveAt), responsibleId, request.reason().trim(), context.userId(), movementId);
        } else {
            movementId = UUID.randomUUID();
            movementCode = codes.next("MOV", request.effectiveDate().getYear());
            jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason, "
                    + "idempotency_key, registered_by_id) "
                    + "values (?, ?, cast(? as movement_type), 'EXECUTED'::movement_status, ?, ?, ?, ?, ?)",
                movementId, movementCode, plannedType(request), Timestamp.from(effectiveAt), responsibleId,
                request.reason().trim(),
                request.idempotencyKey() == null || request.idempotencyKey().isBlank() ? null : request.idempotencyKey(),
                context.userId());
        }

        BigDecimal sourceFinal = sourceBefore.subtract(withdrawn);
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
            if (destinationUnit != null) {
                UUID newLotId = UUID.randomUUID();
                String newLotCode = codes.next("MIX", request.effectiveDate().getYear());
                jdbc.update("insert into lot(id, code, center_id, campaign, entry_date, responsible_id, origin_summary) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", newLotId, newLotCode, centerId,
                    request.effectiveDate().getYear(), request.effectiveDate(), responsibleId,
                    "Mezcla de " + sourceUnit.lotCode() + " y " + destinationUnit.lotCode());
                jdbc.update("update occupation set end_at = ? where id = ?", Timestamp.from(effectiveAt), destinationUnit.occupationId());
                jdbc.update("update content_unit set active = false where id = ?", destinationUnit.contentId());
                resultContentId = UUID.randomUUID();
                resultContentCode = codes.next("C", request.effectiveDate().getYear());
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
                resultContentCode = codes.next("C", request.effectiveDate().getYear());
                jdbc.update("insert into content_unit(id, code, lot_id, category_id, color_id, volume_liters) "
                        + "select ?, ?, lot_id, category_id, color_id, ? from content_unit where id = ?",
                    resultContentId, resultContentCode, request.volumeLiters(), sourceUnit.contentId());
                lineage(resultContentId, sourceUnit.contentId(), movementId, request.volumeLiters());
            }
            jdbc.update("insert into occupation(id, content_unit_id, deposit_id, start_at, volume_liters) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), resultContentId, destination.id(), Timestamp.from(effectiveAt), destinationFinal);
            jdbc.update("update deposit set status = 'OCCUPIED'::deposit_status, updated_at = now() where id = ?", destination.id());
        }
        BigDecimal sourceAfter = sourceFinal;
        BigDecimal destinationAfter = destination == null ? null : destinationFinal;
        jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, "
                + "destination_content_unit_id, destination_deposit_id, volume_liters, loss_liters, "
                + "source_before_liters, source_after_liters, destination_before_liters, destination_after_liters) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), movementId, sourceUnit.contentId(),
            source.id(), resultContentId, destination == null ? null : destination.id(), request.volumeLiters(), request.lossLiters(),
            sourceBefore, sourceAfter, destination == null ? null : destinationBefore, destinationAfter);
        if (destinationUnit != null) {
            jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, "
                    + "destination_content_unit_id, destination_deposit_id, volume_liters, "
                    + "destination_before_liters, destination_after_liters) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), movementId, destinationUnit.contentId(), destination.id(), resultContentId,
                destination.id(), destinationUnit.volume(), destinationBefore, destinationAfter);
        }
        String movementType = plannedCode != null ? plannedType(request)
            : (destinationUnit != null ? "MIX"
                : withdrawn.compareTo(sourceBefore) == 0 ? "TRANSFER_FULL" : "TRANSFER_PARTIAL");
        jdbc.update("update movement set type = cast(? as movement_type) where id = ?", movementType, movementId);
        audit.record("movement", movementId, plannedCode == null ? "MOVEMENT_REGISTERED" : "MOVEMENT_EXECUTED",
            movementType + " · " + source.code() + " → " + (destination == null ? "salida" : destination.code())
                + " · " + request.volumeLiters() + " L · motivo: " + request.reason().trim());
        return new MovementResponse(movementCode, destinationUnit != null, sourceFinal, resultContentCode,
            destinationFinal, "EXECUTED", effectiveAt);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Clears the active content of a deposit by recording a {@code LOSS} movement.
     * Used to undo a mistaken entry without keeping physical content in the deposit.
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
        String movementCode = codes.next("MOV", LocalDate.now(timezone).getYear());
        String trimmedReason = reason == null || reason.isBlank() ? "Corrección manual" : reason.trim();
        jdbc.update("insert into movement(id, code, type, status, effective_at, responsible_id, reason, registered_by_id) "
                + "values (?, ?, 'LOSS'::movement_type, 'EXECUTED'::movement_status, ?, ?, ?, ?)",
            movementId, movementCode, Timestamp.from(effectiveAt), responsiblePk, trimmedReason, context.userId());
        jdbc.update("insert into movement_line(id, movement_id, source_content_unit_id, source_deposit_id, volume_liters) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), movementId, sourceUnit.contentId(), source.id(), sourceUnit.volume());
        jdbc.update("update occupation set end_at = ?, volume_liters = 0 where id = ?", Timestamp.from(effectiveAt), sourceUnit.occupationId());
        jdbc.update("update content_unit set volume_liters = 0, active = false where id = ?", sourceUnit.contentId());
        jdbc.update("update deposit set status = 'PENDING_CLEANING'::deposit_status, updated_at = now() where id = ?", source.id());
        audit.record("movement", movementId, "MOVEMENT_CLEARED",
            "Corrección manual: contenido de " + source.code() + " retirado (" + sourceUnit.volume() + " L).");
    }

    private MovementRequest loadPlanned(String code) {
        MovementRow row = loadMovement(code, context.centerId());
        if (!"PLANNED".equals(row.status())) {
            throw new BusinessRuleException("Solo se pueden ejecutar movimientos en estado PLANNED.");
        }
        if (row.plannedSource() == null) {
            throw new BusinessRuleException("El movimiento previsto no guarda el cuerpo original; redefine el movimiento.");
        }
        // Reconstruct the original request from the stored columns. Time is preserved in
        // effective_at; idempotency keys are intentionally dropped so the executor stamps a
        // fresh one (otherwise the second call would return the original response).
        return new MovementRequest(
            row.type() != null && row.type().equalsIgnoreCase("EXIT") ? "Salida" : "Trasiego",
            row.effectiveAt().atZone(timezone).toLocalDate(),
            row.effectiveAt().atZone(timezone).toLocalTime(),
            row.responsible() == null ? "" : row.responsible(),
            row.reason() == null ? "" : row.reason(),
            row.plannedSource(),
            row.plannedDestination(),
            row.plannedVolume(),
            row.plannedLoss(),
            null,
            Boolean.TRUE.equals(row.plannedAuthorizeMixture()),
            null,
            null,
            false);
    }

    private String plannedType(MovementRequest request) {
        // F4-01: stored as TRANSFER_PARTIAL while PLANNED; the executor recomputes MIX / TRANSFER_FULL on run.
        return request.type().equalsIgnoreCase("Salida") ? "EXIT" : "TRANSFER_PARTIAL";
    }

    private void validateRequestType(MovementRequest request) {
        if (!request.type().equalsIgnoreCase("Salida") && !request.type().equalsIgnoreCase("Trasiego") && !request.type().equalsIgnoreCase("Trasvase")) {
            throw new BusinessRuleException("Tipo de movimiento no soportado.");
        }
        if (!request.type().equalsIgnoreCase("Salida") && (request.destinationDeposit() == null || request.destinationDeposit().isBlank())) {
            throw new BusinessRuleException("El depósito de destino es obligatorio.");
        }
    }

    /** F2-04: return the original {@link MovementResponse} for a previously used idempotency key. */
    private MovementResponse findByIdempotencyKey(String idempotencyKey) {
        List<MovementResponse> existing = jdbc.query("""
            select m.code, m.type::text as type, m.status::text as status, m.effective_at,
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
                rs.getBigDecimal("destination_final"),
                rs.getString("status"),
                rs.getTimestamp("effective_at").toInstant()),
            idempotencyKey);
        return existing.isEmpty() ? null : existing.getFirst();
    }

    private MovementRow loadMovement(String code, UUID centerId) {
        List<MovementRow> rows = jdbc.query("""
            select m.id, m.code, m.type::text as type, m.status::text as status, m.effective_at, m.registered_at,
                   ru.username as responsible_username, rg.username as registered_by_username,
                   m.reason, m.cancelled_reason,
                   m.planned_source_deposit, m.planned_destination_deposit,
                   m.planned_volume_liters, m.planned_loss_liters, m.planned_authorize_mixture
              from movement m
              left join app_user ru on ru.id = m.responsible_id
              left join app_user rg on rg.id = m.registered_by_id
             where m.center_id = ? and upper(m.code) = upper(?)
            """,
            (rs, index) -> new MovementRow(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("type"), rs.getString("status"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("registered_at").toInstant(),
                rs.getString("responsible_username"),
                rs.getString("registered_by_username"),
                rs.getString("reason"), rs.getString("cancelled_reason"),
                rs.getString("planned_source_deposit"), rs.getString("planned_destination_deposit"),
                rs.getBigDecimal("planned_volume_liters"), rs.getBigDecimal("planned_loss_liters"),
                rs.getBoolean("planned_authorize_mixture")),
            centerId, code == null ? "" : code.trim());
        if (rows.isEmpty()) throw new NotFoundException("Movimiento no encontrado: " + code);
        return rows.getFirst();
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

    private String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] parts = value.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) if (!part.isBlank()) out.add(part);
        return out;
    }

    private record DepositSlot(UUID id, String code, UUID zoneId, String status, BigDecimal capacity) {}
    private record OccupiedUnit(UUID occupationId, UUID contentId, String contentCode, String lotCode,
                                BigDecimal volume, Instant startedAt) {}
    private record MovementRow(UUID id, String code, String type, String status, Instant effectiveAt, Instant registeredAt,
                              String responsible, String registeredBy, String reason, String cancelledReason,
                              String plannedSource, String plannedDestination,
                              BigDecimal plannedVolume, BigDecimal plannedLoss, Boolean plannedAuthorizeMixture) {}
}