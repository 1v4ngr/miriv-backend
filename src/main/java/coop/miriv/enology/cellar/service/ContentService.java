package coop.miriv.enology.cellar.service;

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

    public ContentService(JdbcTemplate jdbc, DepositService deposits, LotService lots,
                          LaboratoryService laboratory, CurrentUserContext context) {
        this.jdbc = jdbc;
        this.deposits = deposits;
        this.lots = lots;
        this.laboratory = laboratory;
        this.context = context;
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
        if (locations.isEmpty()) throw new NotFoundException("Content unit not found.");
        ContentLocation location = locations.getFirst();
        DepositResponse deposit = deposits.get(location.depositCode());
        OccupationResponse occupation = deposit.occupations().stream()
            .filter(item -> item.contentCode().equals(normalized)).findFirst()
            .orElseThrow(() -> new NotFoundException("Content occupation not found."));
        String plan = jdbc.query("select name from elaboration_plan where content_unit_id = ?",
            (rs, index) -> rs.getString(1), location.id()).stream().findFirst().orElse("No plan assigned");
        var samples = laboratory.list().stream().filter(item -> item.contentCode().equals(normalized)).toList();
        return new ContentResponse(normalized, lots.get(location.lotCode()), deposit, occupation, location.active(),
            plan, state(location.id(), "ALCOHOLIC"), state(location.id(), "MALOLACTIC"), samples);
    }

    @Transactional
    public void review(String code, StateReviewRequest request) {
        String process = switch (request.process()) {
            case "alcoholic" -> "ALCOHOLIC";
            case "malolactic" -> "MALOLACTIC";
            default -> throw new BusinessRuleException("Unsupported fermentation process.");
        };
        Set<String> allowed = process.equals("ALCOHOLIC")
            ? Set.of("No iniciada", "Activa", "Lenta", "Sospecha de parada", "Finalizada")
            : Set.of("No iniciada", "Activa", "Lenta", "Finalizada", "No prevista");
        if (!allowed.contains(request.decision())) throw new BusinessRuleException("Unsupported state decision.");
        ContentResponse content = get(code);
        if (!content.active()) throw new BusinessRuleException("Only active content can have a state confirmed.");
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
    }

    private FermentationStateResponse state(UUID contentId, String process) {
        List<FermentationStateResponse> values = jdbc.query("select estimated_status, confirmed_status, "
                + "coalesce(estimated_at::text, '') as date, "
                + "(select pv.malolactic_intent::text from elaboration_plan p "
                + "join plan_version pv on pv.id = p.current_version_id where p.content_unit_id = ?) as intent "
                + "from fermentation_state where content_unit_id = ? and process = cast(? as fermentation_process)",
            (rs, index) -> new FermentationStateResponse(rs.getString("estimated_status"),
                rs.getString("confirmed_status") == null ? "Unconfirmed" : rs.getString("confirmed_status"),
                intent(rs.getString("intent")), rs.getString("date")), contentId, contentId, process);
        return values.isEmpty() ? new FermentationStateResponse("Not evaluated", "Unconfirmed",
            process.equals("MALOLACTIC") ? "Not decided in plan" : "", "No recent control") : values.getFirst();
    }

    private String intent(String value) {
        if (value == null) return "Not decided in plan";
        return switch (value) {
            case "PLANNED" -> "Planned";
            case "NOT_DESIRED" -> "Not planned";
            default -> "Not decided in plan";
        };
    }

    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private record ContentLocation(UUID id, String lotCode, String depositCode, boolean active) {}
}
