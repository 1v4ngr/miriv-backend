package coop.miriv.enology.tracking.web;

import coop.miriv.enology.tracking.dto.TrackingDto.TargetRequest;
import coop.miriv.enology.tracking.dto.TrackingDto.TargetView;
import coop.miriv.enology.tracking.service.ParameterTargetService;
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

/** Warning / critical ranges per parameter (LAB_CATALOG_MANAGE, see PermissionRules). */
@RestController
@RequestMapping("/api/admin/parameter-targets")
public class ParameterTargetController {

    private final ParameterTargetService service;

    public ParameterTargetController(ParameterTargetService service) { this.service = service; }

    @GetMapping
    public List<TargetView> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TargetView create(@RequestBody TargetRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public TargetView update(@PathVariable UUID id, @RequestBody TargetRequest request) { return service.update(id, request); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { service.delete(id); }
}
