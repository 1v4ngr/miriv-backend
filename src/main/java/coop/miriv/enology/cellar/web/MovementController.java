package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.MovementRequest;
import coop.miriv.enology.cellar.dto.MovementResponse;
import coop.miriv.enology.cellar.service.MovementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'CELLAR_OPERATOR', 'PRODUCTION_MANAGER')")
    public MovementResponse register(@Valid @RequestBody MovementRequest request) { return service.register(request); }
}
