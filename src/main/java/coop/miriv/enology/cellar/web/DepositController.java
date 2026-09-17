package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.DepositRequest;
import coop.miriv.enology.cellar.dto.DepositResponse;
import coop.miriv.enology.cellar.dto.UpdateDepositRequest;
import coop.miriv.enology.cellar.service.DepositService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final DepositService service;

    public DepositController(DepositService service) {
        this.service = service;
    }

    @GetMapping
    public List<DepositResponse> list() {
        return service.list();
    }

    @GetMapping("/{code}")
    public DepositResponse get(@PathVariable String code) {
        return service.get(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER', 'ADMIN')")
    public DepositResponse create(@Valid @RequestBody DepositRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{code}")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER', 'ADMIN')")
    public DepositResponse update(@PathVariable String code,
                                  @Valid @RequestBody UpdateDepositRequest request) {
        return service.update(code, request);
    }
}
