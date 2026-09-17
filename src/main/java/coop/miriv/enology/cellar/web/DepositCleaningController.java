package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.CompleteCleaningRequest;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.service.DepositCleaningService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deposits/{code}/cleaning")
public class DepositCleaningController {

    private final DepositCleaningService service;

    public DepositCleaningController(DepositCleaningService service) { this.service = service; }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'CELLAR_OPERATOR', 'PRODUCTION_MANAGER')")
    public DepositResponse start(@PathVariable String code) { return service.start(code); }

    @PostMapping("/complete")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'CELLAR_OPERATOR', 'PRODUCTION_MANAGER')")
    public DepositResponse complete(@PathVariable String code, @Valid @RequestBody CompleteCleaningRequest request) {
        return service.complete(code, request);
    }
}
