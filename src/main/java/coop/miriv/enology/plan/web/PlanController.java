package coop.miriv.enology.plan.web;

import coop.miriv.enology.plan.dto.CreatePlanRequest;
import coop.miriv.enology.plan.dto.PlanResponse;
import coop.miriv.enology.plan.dto.PlanVersionRequest;
import coop.miriv.enology.plan.service.PlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans/contents/{contentCode}")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) { this.service = service; }

    @GetMapping
    public PlanResponse get(@PathVariable String contentCode) { return service.get(contentCode); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ENOLOGIST')")
    public PlanResponse create(@PathVariable String contentCode, @Valid @RequestBody CreatePlanRequest request) {
        return service.create(contentCode, request);
    }

    @PostMapping("/versions")
    @PreAuthorize("hasRole('ENOLOGIST')")
    public PlanResponse addVersion(@PathVariable String contentCode, @Valid @RequestBody PlanVersionRequest request) {
        return service.addVersion(contentCode, request);
    }
}
