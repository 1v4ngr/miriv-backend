package coop.miriv.enology.incident.web;

import coop.miriv.enology.incident.dto.AssignIncidentRequest;
import coop.miriv.enology.incident.dto.IncidentResponse;
import coop.miriv.enology.incident.dto.ResolveIncidentRequest;
import coop.miriv.enology.incident.dto.SilenceIncidentRequest;
import coop.miriv.enology.incident.service.IncidentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) { this.service = service; }

    @GetMapping
    public List<IncidentResponse> list() { return service.list(); }

    @GetMapping("/{code}")
    public IncidentResponse get(@PathVariable String code) { return service.get(code); }

    @PostMapping("/{code}/acknowledge")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'LABORATORY', 'CELLAR_OPERATOR', 'PRODUCTION_MANAGER')")
    public IncidentResponse acknowledge(@PathVariable String code) { return service.acknowledge(code); }

    @PostMapping("/{code}/assign")
    @PreAuthorize("hasAnyRole('ENOLOGIST', 'PRODUCTION_MANAGER')")
    public IncidentResponse assign(@PathVariable String code, @Valid @RequestBody AssignIncidentRequest request) {
        return service.assign(code, request.responsible());
    }

    @PostMapping("/{code}/silence")
    @PreAuthorize("hasRole('ENOLOGIST')")
    public IncidentResponse silence(@PathVariable String code, @Valid @RequestBody SilenceIncidentRequest request) {
        return service.silence(code, request.until(), request.reason());
    }

    @PostMapping("/{code}/resolve")
    @PreAuthorize("hasRole('ENOLOGIST')")
    public IncidentResponse resolve(@PathVariable String code, @Valid @RequestBody ResolveIncidentRequest request) {
        return service.close(code, request, false);
    }

    @PostMapping("/{code}/discard")
    @PreAuthorize("hasRole('ENOLOGIST')")
    public IncidentResponse discard(@PathVariable String code, @Valid @RequestBody ResolveIncidentRequest request) {
        return service.close(code, request, true);
    }
}
