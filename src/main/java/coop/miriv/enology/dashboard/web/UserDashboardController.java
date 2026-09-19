package coop.miriv.enology.dashboard.web;

import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardRequest;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardSummary;
import coop.miriv.enology.dashboard.dto.UserDashboardDto.DashboardView;
import coop.miriv.enology.dashboard.service.UserDashboardService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Personal tracking dashboards. Under /api/account/**, so any authenticated user (SecurityConfig). */
@RestController
@RequestMapping("/api/account/dashboards")
public class UserDashboardController {

    private final UserDashboardService service;

    public UserDashboardController(UserDashboardService service) { this.service = service; }

    @GetMapping
    public List<DashboardSummary> list() { return service.list(); }

    @GetMapping("/{id}")
    public DashboardView get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DashboardView create(@Valid @RequestBody DashboardRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public DashboardView update(@PathVariable UUID id, @Valid @RequestBody DashboardRequest request) { return service.update(id, request); }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public DashboardView duplicate(@PathVariable UUID id) { return service.duplicate(id); }

    @PostMapping("/{id}/default")
    public DashboardView makeDefault(@PathVariable UUID id) { return service.makeDefault(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id); }
}
