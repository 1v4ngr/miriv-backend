package coop.miriv.enology.cellar.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.cellar.dto.ContentResponse;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.dto.FermentationStateResponse;
import coop.miriv.enology.cellar.dto.OccupationResponse;
import coop.miriv.enology.cellar.dto.StateReviewRequest;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentService {

    private final JdbcTemplate jdbc;
    private final DepositService deposits;
    private final LotService lots;
    private final LaboratoryService laboratory;
    private final CurrentUserContext context;
    private final AuditService audit;

    public ContentService(JdbcTemplate jdbc, DepositService deposits, LotService lots,
                          LaboratoryService laboratory, CurrentUserContext context, AuditService audit) {
        this.jdbc = jdbc;
        this.deposits = deposits;
        this.lots = lots;
        this.laboratory = laboratory;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ContentResponse get(String code) {
        UUID centerId = context.centerId();
        String normalized = normalize(code);
        List<ContentLocation> locations = jdbc.query("select cu.id, l.code as lot_code, d.code as deposit_code, "
                + "o.end_at is null as active from content_unit cu join lot l on l.id = cu.lot_id "
                + "join occupation o on o.content_unit_id = cu.id join deposit d on d.id = o.deposit_id "
                + "where l.center_id = ? and cu.code = ? order by o.start_at desc limit 1",
            (rs, index) -> new ContentLocation(rs.getObject("id", UUID.class), rs.getString("lot_code"),
                rs.getString("deposit_code"), rs.getBoolean("active")), centerId, normalized);
        if (locations.isEmpty()) throw new NotFoundException("Unidad de contenido no encontrada.");
        ContentLocation location = locations.getFirst();
        DepositResponse deposit = deposits.get(location.depositCode());
        OccupationResponse occupation = deposit.occupations().stream()
            .filter(item -> item.contentCode().equals(normalized)).findFirst()
            .orElseThrow(() -> new NotFoundException("Ocupación de contenido no encontrada."));
        // F2-03: plan is now nullable; the front renders "Sin plan asignado" when null.
        String plan = jdbc.query("select name from elaboration_plan where content_unit_id = ?",
            (rs, index) -> rs.getString(1), location.id()).stream().findFirst().orElse(null);
        // F2-07: ask the lab for samples scoped to this content instead of scanning the whole center.
        var samples = laboratory.listByContent(normalized);
        return new ContentResponse(normalized, lots.get(location.lotCode()), deposit, occupation, location.active(),
            plan, state(location.id(), "ALCOHOLIC"), state(location.id(), "MALOLACTIC"), samples);
    }

    @Transactional
    public void review(String code, StateReviewRequest request) {
        String process = switch (request.process()) {
            case "alcoholic" -> "ALCOHOLIC";
            case "malolactic" -> "MALOLACTIC";
            default -> throw new BusinessRuleException("Proceso de fermentación no soportado.");
        };
        Set<String> allowed = process.equals("ALCOHOLIC")
            ? Set.of("No iniciada", "Activa", "Lenta", "Sospecha de parada", "Finalizada")
            : Set.of("No iniciada", "Activa", "Lenta", "Finalizada", "No prevista");
        if (!allowed.contains(request.decision())) throw new BusinessRuleException("Decisión de estado no soportada.");
        ContentResponse content = get(code);
        if (!content.active()) throw new BusinessRuleException("Solo el contenido activo puede tener un estado confirmado.");
        UUID actor = context.userId();
        UUID contentId = jdbc.queryForObject("select id from content_unit where code = ?", UUID.class, normalize(code));
        String previous = jdbc.query("select confirmed_status from fermentation_state where content_unit_id = ? "
                + "and process = cast(? as fermentation_process) for update",
            (rs, index) -> rs.getString(1), contentId, process).stream().findFirst().orElse(null);
        jdbc.update("insert into fermentation_state_review(id, content_unit_id, process, previous_status, "
                + "decision, reason, reviewed_by_id) values (?, ?, cast(? as fermentation_process), ?, ?, ?, ?)",
            UUID.randomUUID(), contentId, process, previous, request.decision(), request.reason().trim(), actor);
        jdbc.update("insert into fermentation_state(id, content_unit_id, process, estimated_status, confirmed_status, "
                + "confirmed_by_id, confirmed_at, confirmation_reason) "
                + "values (?, ?, cast(? as fermentation_process), 'NOT_EVALUABLE', ?, ?, now(), ?) "
                + "on conflict (content_unit_id, process) do update set confirmed_status = excluded.confirmed_status, "
                + "confirmed_by_id = excluded.confirmed_by_id, confirmed_at = excluded.confirmed_at, "
                + "confirmation_reason = excluded.confirmation_reason",
            UUID.randomUUID(), contentId, process, request.decision(), actor, request.reason().trim());
        audit.record("content_unit", contentId, "STATE_REVIEWED",
            process + " → " + request.decision() + " · " + request.reason().trim());
    }

    // F2-03: fermentation state / intent values become stable codes on the wire; the front
    // maps them to localized labels in src/lib/labels.ts. The confirmed status stays in
    // Spanish because those are deliberate enologist decisions, not enum tokens.
    private FermentationStateResponse state(UUID contentId, String process) {
        List<FermentationStateResponse> values = jdbc.query("select estimated_status, confirmed_status, "
                + "coalesce(estimated_at::text, '') as date, "
                + "(select pv.malolactic_intent::text from elaboration_plan p "
                + "join plan_version pv on pv.id = p.current_version_id where p.content_unit_id = ?) as intent "
                + "from fermentation_state where content_unit_id = ? and process = cast(? as fermentation_process)",
            (rs, index) -> new FermentationStateResponse(rs.getString("estimated_status"),
                rs.getString("confirmed_status"),
                intent(rs.getString("intent")),
                rs.getString("date")), contentId, contentId, process);
        if (values.isEmpty()) {
            return new FermentationStateResponse("NOT_EVALUATED", null,
                process.equals("MALOLACTIC") ? "PENDING_DECISION" : null, null);
        }
        return values.getFirst();
    }

    private String intent(String value) {
        if (value == null) return "PENDING_DECISION";
        return switch (value) {
            case "PLANNED" -> "PLANNED";
            case "NOT_DESIRED" -> "NOT_DESIRED";
            default -> "PENDING_DECISION";
        };
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private record ContentLocation(UUID id, String lotCode, String depositCode, boolean active) {}
}
