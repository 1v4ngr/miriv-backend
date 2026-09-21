package coop.miriv.enology.blend.web;

import coop.miriv.enology.blend.dto.BlendDto.BlendRequest;
import coop.miriv.enology.blend.dto.BlendDto.BlendSummary;
import coop.miriv.enology.blend.dto.BlendDto.BlendView;
import coop.miriv.enology.blend.service.BlendSimulationService;
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

/** Saved blend simulations. Reads: any authenticated user; writes: MOVEMENT_PLAN (see PermissionRules). */
@RestController
@RequestMapping("/api/blends")
public class BlendSimulationController {

    private final BlendSimulationService service;

    public BlendSimulationController(BlendSimulationService service) { this.service = service; }

    @GetMapping
    public List<BlendSummary> list() { return service.list(); }

    @GetMapping("/{id}")
    public BlendView get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BlendView create(@Valid @RequestBody BlendRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public BlendView update(@PathVariable UUID id, @Valid @RequestBody BlendRequest request) { return service.update(id, request); }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public BlendView duplicate(@PathVariable UUID id) { return service.duplicate(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id); }
}
