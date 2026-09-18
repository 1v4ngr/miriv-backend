package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.ClearContentRequest;
import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.cellar.service.MovementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movements")
public class MovementController {

    private final MovementService service;

    public MovementController(MovementService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MovementResponse register(@Valid @RequestBody MovementRequest request) { return service.register(request); }

    /**
     * Clears the active content of a deposit by recording a {@code LOSS} movement.
     * Intended for undoing a mistaken entry; preserves the audit trail.
     */
    @PostMapping("/deposits/{code}/content-clearance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDepositContent(@PathVariable String code, @Valid @RequestBody ClearContentRequest request) {
        service.clearOccupation(code, request.reason(), request.responsible());
    }
}