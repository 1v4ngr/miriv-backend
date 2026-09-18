package coop.miriv.enology.cellar.web;

import coop.miriv.enology.cellar.dto.CreateLotRequest;
import coop.miriv.enology.cellar.dto.LotArchiveRequest;
import coop.miriv.enology.cellar.dto.LotResponse;
import coop.miriv.enology.cellar.dto.LineageEventResponse;
import coop.miriv.enology.cellar.dto.UpdateLotRequest;
import coop.miriv.enology.cellar.service.LotService;
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
@RequestMapping("/api/lots")
public class LotController {

    private final LotService service;

    public LotController(LotService service) { this.service = service; }

    @GetMapping
    public List<LotResponse> list() { return service.list(); }

    @GetMapping("/{code}")
    public LotResponse get(@PathVariable String code) { return service.get(code); }

    @GetMapping("/{code}/genealogy")
    public List<LineageEventResponse> genealogy(@PathVariable String code) { return service.genealogy(code); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LotResponse create(@Valid @RequestBody CreateLotRequest request) { return service.create(request); }

    @PatchMapping("/{code}")
    public LotResponse update(@PathVariable String code, @Valid @RequestBody UpdateLotRequest request) {
        return service.update(code, request);
    }

    @PostMapping("/{code}/archive")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER', 'ADMIN')")
    public LotResponse archive(@PathVariable String code, @Valid @RequestBody LotArchiveRequest request) {
        return service.archive(code, request.reason());
    }

    @PostMapping("/{code}/reopen")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER', 'ADMIN')")
    public LotResponse reopen(@PathVariable String code, @Valid @RequestBody LotArchiveRequest request) {
        return service.reopen(code, request.reason());
    }
}
