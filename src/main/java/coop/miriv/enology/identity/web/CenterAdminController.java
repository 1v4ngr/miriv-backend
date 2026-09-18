package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.*;
import coop.miriv.enology.identity.service.CenterAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin/centers")
public class CenterAdminController {
    private final CenterAdminService service;
    public CenterAdminController(CenterAdminService service) { this.service = service; }
    @GetMapping public List<CenterOption> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CenterOption create(@Valid @RequestBody CenterAdminRequest r) { return service.create(r); }
    @PutMapping("/{code}") public CenterOption update(@PathVariable String code, @Valid @RequestBody CenterAdminRequest r) { return service.update(code, r); }
    @DeleteMapping("/{code}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String code) { service.delete(code); }
}