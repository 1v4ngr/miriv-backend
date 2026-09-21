package coop.miriv.enology.cellar.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.SuperAdminCheck;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixing a movement that was entered wrong, without going into the database by hand.
 *
 * <p>Two operations, both audited: <b>reschedule</b> moves the date, the time and the reason, dragging
 * along the occupations the movement opened or closed (and the lot's entry date when it is the initial
 * entry); <b>undo</b> deletes the movement and puts the wine back where it was.
 *
 * <p>Both refuse to act when something happened afterwards — a later movement, a sample taken from the
 * resulting wine — because the correction would silently rewrite history that other records rely on.
 */
@Service
public class MovementCorrectionService {

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final AuditService audit;
    private final SuperAdminCheck superAdmin;
    private final ZoneId timezone;

    public MovementCorrectionService(JdbcTemplate jdbc, CurrentUserContext context, AuditService audit,
                                     SuperAdminCheck superAdmin, @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.audit = audit;
        this.superAdmin = superAdmin;
        this.timezone = ZoneId.of(timezone);
    }

    // ------------------------------------------------------------------ reschedule

    /** Moves when the movement happened. Volumes and deposits stay as they are. */
    @Transactional
    public void reschedule(String code, LocalDate date, LocalTime time, String reason) {
        Movement movement = load(code);
        requirePermissionOnDeposits(movement, "MOVEMENT_REGISTER");
        Instant target = date.atTime(time == null ? LocalTime.MIDNIGHT : time).atZone(timezone).toInstant();
        if (target.isAfter(Instant.now())) throw new BusinessRuleException("La fecha efectiva no puede estar en el futuro.");
        if (target.equals(movement.effectiveAt()) && (reason == null || reason.isBlank())) return;

        List<Line> lines = lines(movement.id());
        for (Line line : lines) {
            // The source must already have been in the deposit at the new date.
            if (line.sourceContentId() != null) {
                Instant start = startOfOccupation(line.sourceContentId(), line.sourceDepositId());
                if (start != null && target.isBefore(start)) {
                    throw new BusinessRuleException("La nueva fecha es anterior al inicio de la ocupación del origen.");
                }
            }
            // And nothing that came later may end up before the movement.
            Instant nextMovement = nextMovementAt(line, movement.effectiveAt());
            if (nextMovement != null && !target.isBefore(nextMovement)) {
                throw new BusinessRuleException("Hay un movimiento posterior sobre ese contenido: la nueva fecha debe ser anterior a él.");
            }
            Instant firstSample = firstSampleAt(line.destinationContentId());
            if (firstSample != null && target.isAfter(firstSample)) {
                throw new BusinessRuleException("Hay analíticas de ese contenido anteriores a la nueva fecha.");
            }
        }

        jdbc.update("update movement set effective_at = ?" + (blank(reason) ? "" : ", reason = ?") + " where id = ?",
            blank(reason) ? new Object[] { Timestamp.from(target), movement.id() }
                : new Object[] { Timestamp.from(target), reason.trim(), movement.id() });

        // The occupations this movement opened and closed follow it.
        int opened = jdbc.update("update occupation set start_at = ? where start_at = ? and (content_unit_id, deposit_id) in "
                + "(select destination_content_unit_id, destination_deposit_id from movement_line "
                + "where movement_id = ? and destination_content_unit_id is not null)",
            Timestamp.from(target), Timestamp.from(movement.effectiveAt()), movement.id());
        int closed = jdbc.update("update occupation set end_at = ? where end_at = ? and (content_unit_id, deposit_id) in "
                + "(select source_content_unit_id, source_deposit_id from movement_line "
                + "where movement_id = ? and source_content_unit_id is not null)",
            Timestamp.from(target), Timestamp.from(movement.effectiveAt()), movement.id());
        // An initial entry also dates the lot.
        if ("ENTRY".equals(movement.type())) {
            jdbc.update("update lot set entry_date = ? where id in (select cu.lot_id from movement_line ml "
                + "join content_unit cu on cu.id = ml.destination_content_unit_id where ml.movement_id = ?)",
                date, movement.id());
        }

        audit.record("movement", movement.id(), "MOVEMENT_RESCHEDULED",
            blank(reason) ? "Fecha corregida" : reason.trim(),
            java.util.Map.of("effectiveAt", movement.effectiveAt().toString()),
            java.util.Map.of("effectiveAt", target.toString(), "occupationsOpened", opened, "occupationsClosed", closed));
    }

    // ------------------------------------------------------------------ undo

    /** Deletes the movement and returns every deposit it touched to the state it had before. */
    @Transactional
    public void undo(String code, String reason) {
        superAdmin.require("eliminar un movimiento");
        Movement movement = load(code);
        if (blank(reason)) throw new BusinessRuleException("Indica el motivo de la eliminación.");

        if ("CANCELLED".equals(movement.status()) || "PLANNED".equals(movement.status())) {
            jdbc.update("delete from movement_line where movement_id = ?", movement.id());
            jdbc.update("delete from movement where id = ?", movement.id());
            audit.record("movement", movement.id(), "MOVEMENT_DELETED",
                "Movimiento " + movement.status() + " eliminado: " + reason.trim());
            return;
        }

        List<Line> lines = lines(movement.id());
        if (lines.isEmpty()) throw new BusinessRuleException("El movimiento no tiene líneas que deshacer.");
        guardNothingHappenedAfter(movement, lines);

        Set<UUID> touchedDeposits = new LinkedHashSet<>();
        List<String> undone = new ArrayList<>();
        for (Line line : lines) {
            if (line.destinationContentId() != null && line.destinationDepositId() != null) {
                touchedDeposits.add(line.destinationDepositId());
                // The occupation this movement opened in the destination goes away.
                jdbc.update("delete from occupation where content_unit_id = ? and deposit_id = ? and start_at = ?",
                    line.destinationContentId(), line.destinationDepositId(), Timestamp.from(movement.effectiveAt()));
            }
            if (line.sourceContentId() != null && line.sourceDepositId() != null) {
                touchedDeposits.add(line.sourceDepositId());
                BigDecimal before = line.sourceBefore();
                if (before != null) {
                    // Reopen the occupation this movement closed and give the litres back.
                    jdbc.update("update occupation set end_at = null, volume_liters = ? "
                            + "where content_unit_id = ? and deposit_id = ? and (end_at = ? or end_at is null)",
                        before, line.sourceContentId(), line.sourceDepositId(), Timestamp.from(movement.effectiveAt()));
                    jdbc.update("update content_unit set volume_liters = ?, active = true where id = ?",
                        before, line.sourceContentId());
                    undone.add(contentCode(line.sourceContentId()) + " → " + before + " L");
                }
            }
        }

        // Contents (and mix lots) this movement created disappear with it; the ones it only moved stay.
        for (Line line : lines) {
            UUID content = line.destinationContentId();
            if (content == null || content.equals(line.sourceContentId())) continue;
            boolean createdHere = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from content_unit_lineage where content_unit_id = ? and movement_id = ?)",
                Boolean.class, content, movement.id()));
            if (!createdHere) continue;
            if (hasDependants(content)) {
                throw new BusinessRuleException("El contenido " + contentCode(content)
                    + " tiene registros asociados (analíticas, tareas o incidencias): no se puede deshacer automáticamente.");
            }
            UUID lotId = jdbc.queryForObject("select lot_id from content_unit where id = ?", UUID.class, content);
            jdbc.update("delete from content_unit_lineage where content_unit_id = ? or parent_content_unit_id = ?", content, content);
            jdbc.update("delete from occupation where content_unit_id = ?", content);
            jdbc.update("delete from movement_line where destination_content_unit_id = ? or source_content_unit_id = ?", content, content);
            jdbc.update("delete from content_unit where id = ?", content);
            // A blend lot exists only for its blended content.
            jdbc.update("delete from lot where id = ? and code like 'MIX-%' "
                + "and not exists (select 1 from content_unit where lot_id = ?)", lotId, lotId);
            undone.add(contentCode(content) + " eliminado");
        }

        jdbc.update("delete from content_unit_lineage where movement_id = ?", movement.id());
        jdbc.update("delete from movement_line where movement_id = ?", movement.id());
        jdbc.update("delete from movement where id = ?", movement.id());

        for (UUID depositId : touchedDeposits) refreshDepositStatus(depositId);

        audit.record("movement", movement.id(), "MOVEMENT_UNDONE", reason.trim(),
            java.util.Map.of("code", movement.code(), "type", movement.type(),
                "effectiveAt", movement.effectiveAt().toString()),
            java.util.Map.of("restored", undone));
    }

    /** Undoing only makes sense while the movement is the last thing that happened to the wine it touched. */
    private void guardNothingHappenedAfter(Movement movement, List<Line> lines) {
        for (Line line : lines) {
            for (UUID content : new UUID[] { line.sourceContentId(), line.destinationContentId() }) {
                if (content == null) continue;
                Integer later = jdbc.queryForObject("select count(*) from movement_line ml "
                        + "join movement m on m.id = ml.movement_id "
                        + "where m.id <> ? and m.status = 'EXECUTED'::movement_status and m.effective_at >= ? "
                        + "and (ml.source_content_unit_id = ? or ml.destination_content_unit_id = ?)",
                    Integer.class, movement.id(), Timestamp.from(movement.effectiveAt()), content, content);
                if (later != null && later > 0) {
                    throw new BusinessRuleException("Hay movimientos posteriores sobre " + contentCode(content)
                        + ": deshaz primero el último.");
                }
                Integer samples = jdbc.queryForObject(
                    "select count(*) from sample where content_unit_id = ? and taken_at >= ?",
                    Integer.class, content, Timestamp.from(movement.effectiveAt()));
                if (samples != null && samples > 0) {
                    throw new BusinessRuleException("Hay analíticas de " + contentCode(content)
                        + " posteriores al movimiento: elimínalas o corrígelas antes.");
                }
            }
        }
    }

    private boolean hasDependants(UUID contentId) {
        Integer count = jdbc.queryForObject("""
            select (select count(*) from sample where content_unit_id = ?)
                 + (select count(*) from elaboration_plan where content_unit_id = ?)
                 + (select count(*) from fermentation_state where content_unit_id = ?)
            """, Integer.class, contentId, contentId, contentId);
        return count != null && count > 0;
    }

    /** OCCUPIED while something is inside, AVAILABLE once the deposit is empty again. */
    private void refreshDepositStatus(UUID depositId) {
        boolean occupied = Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from occupation where deposit_id = ? and end_at is null)", Boolean.class, depositId));
        jdbc.update("update deposit set status = cast(? as deposit_status), updated_at = now() "
                + "where id = ? and status in ('OCCUPIED','AVAILABLE','PENDING_CLEANING')",
            occupied ? "OCCUPIED" : "AVAILABLE", depositId);
    }

    private void requirePermissionOnDeposits(Movement movement, String permission) {
        List<UUID> zones = jdbc.query("select distinct d.zone_id from movement_line ml "
                + "join deposit d on d.id in (ml.source_deposit_id, ml.destination_deposit_id) "
                + "where ml.movement_id = ?",
            (rs, index) -> rs.getObject(1, UUID.class), movement.id());
        if (zones.isEmpty()) {
            if (!context.has(permission)) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "No tienes permiso para corregir movimientos.");
            }
            return;
        }
        zones.forEach(zoneId -> context.requireInZone(permission, zoneId));
    }

    // ------------------------------------------------------------------ reads

    private Movement load(String code) {
        UUID centerId = context.centerId();
        List<Movement> rows = jdbc.query("""
            select m.id, m.code, m.type::text, m.status::text, m.effective_at
              from movement m
             where upper(m.code) = ?
               and (exists (select 1 from movement_line ml join deposit d
                              on d.id in (ml.source_deposit_id, ml.destination_deposit_id)
                            where ml.movement_id = m.id and d.center_id = ?)
                or exists (select 1 from deposit d where d.center_id = ?
                            and d.code in (m.planned_source_deposit, m.planned_destination_deposit)))
            """,
            (rs, index) -> new Movement(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("type"),
                rs.getString("status"), rs.getTimestamp("effective_at").toInstant()),
            code.trim().toUpperCase(Locale.ROOT), centerId, centerId);
        if (rows.isEmpty()) throw new NotFoundException("Movimiento no encontrado.");
        return rows.getFirst();
    }

    private List<Line> lines(UUID movementId) {
        return jdbc.query("select source_content_unit_id, source_deposit_id, destination_content_unit_id, "
                + "destination_deposit_id, source_before_liters, destination_before_liters, volume_liters "
                + "from movement_line where movement_id = ?",
            (rs, index) -> new Line(rs.getObject("source_content_unit_id", UUID.class),
                rs.getObject("source_deposit_id", UUID.class),
                rs.getObject("destination_content_unit_id", UUID.class),
                rs.getObject("destination_deposit_id", UUID.class),
                rs.getBigDecimal("source_before_liters"), rs.getBigDecimal("destination_before_liters"),
                rs.getBigDecimal("volume_liters")), movementId);
    }

    private Instant startOfOccupation(UUID contentId, UUID depositId) {
        return jdbc.query("select start_at from occupation where content_unit_id = ? and deposit_id = ? order by start_at limit 1",
            (rs, index) -> rs.getTimestamp(1).toInstant(), contentId, depositId).stream().findFirst().orElse(null);
    }

    private Instant nextMovementAt(Line line, Instant after) {
        List<UUID> contents = new ArrayList<>();
        if (line.sourceContentId() != null) contents.add(line.sourceContentId());
        if (line.destinationContentId() != null) contents.add(line.destinationContentId());
        if (contents.isEmpty()) return null;
        String marks = String.join(",", contents.stream().map(item -> "?").toList());
        List<Object> args = new ArrayList<>(contents);
        args.addAll(contents);
        args.add(Timestamp.from(after));
        return jdbc.query("select min(m.effective_at) from movement m join movement_line ml on ml.movement_id = m.id "
                + "where m.status = 'EXECUTED'::movement_status "
                + "and (ml.source_content_unit_id in (" + marks + ") or ml.destination_content_unit_id in (" + marks + ")) "
                + "and m.effective_at > ?",
            (rs, index) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
            args.toArray()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private Instant firstSampleAt(UUID contentId) {
        if (contentId == null) return null;
        return jdbc.query("select min(taken_at) from sample where content_unit_id = ?",
            (rs, index) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), contentId)
            .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private String contentCode(UUID contentId) {
        return jdbc.query("select code from content_unit where id = ?", (rs, index) -> rs.getString(1), contentId)
            .stream().findFirst().orElse("(contenido eliminado)");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record Movement(UUID id, String code, String type, String status, Instant effectiveAt) {}

    private record Line(UUID sourceContentId, UUID sourceDepositId, UUID destinationContentId,
                        UUID destinationDepositId, BigDecimal sourceBefore, BigDecimal destinationBefore,
                        BigDecimal volume) {}
}
