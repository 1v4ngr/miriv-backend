package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.CancelMovementRequest;
import coop.miriv.enology.cellar.dto.ClearContentRequest;
import coop.miriv.enology.cellar.dto.MovementDetailResponse;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.RescheduleMovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.cellar.dto.MovementSummaryResponse;
import coop.miriv.enology.cellar.service.MovementCorrectionService;
import coop.miriv.enology.cellar.service.MovementService;
import coop.miriv.enology.common.dto.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movements")
public class MovementController {

    private final MovementService service;
    private final MovementCorrectionService corrections;

    public MovementController(MovementService service, MovementCorrectionService corrections) {
        this.service = service;
        this.corrections = corrections;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MovementResponse register(@Valid @RequestBody MovementRequest request) { return service.register(request); }

    /** F4-01: list the history of movements visible to the caller (paged). */
    @GetMapping
    public PageResponse<MovementSummaryResponse> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String deposit,
            @RequestParam(required = false) String lot,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(from, to, deposit, lot, type, status, author, q, page, size);
    }

    /** F4-01: full detail of a movement (UI15 receipt). */
    @GetMapping("/{code}")
    public MovementDetailResponse get(@PathVariable String code) { return service.get(code); }

    /** F4-01: execute a previously PLANNED movement. */
    @PostMapping("/{code}/execute")
    public MovementResponse execute(@PathVariable String code) { return service.execute(code); }

    /** F4-01: cancel a PLANNED movement; executed ones must be corrected, not cancelled. */
    @PostMapping("/{code}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String code, @Valid @RequestBody CancelMovementRequest request) {
        service.cancel(code, request);
    }

    /**
     * Clears the active content of a deposit by recording a {@code LOSS} movement.
     * Intended for undoing a mistaken entry; preserves the audit trail.
     */
    /** Fixes the date/time (and reason) of a movement entered wrong; occupations follow it. */
    @PatchMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reschedule(@PathVariable String code, @Valid @RequestBody RescheduleMovementRequest request) {
        corrections.reschedule(code, request.effectiveDate(), request.effectiveTime(), request.reason());
    }

    /** Deletes a movement and puts the wine back where it was; super administrator only. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undo(@PathVariable String code, @RequestParam String reason) { corrections.undo(code, reason); }

    @PostMapping("/deposits/{code}/content-clearance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDepositContent(@PathVariable String code, @Valid @RequestBody ClearContentRequest request) {
        service.clearOccupation(code, request.reason(), request.responsible());
    }
}