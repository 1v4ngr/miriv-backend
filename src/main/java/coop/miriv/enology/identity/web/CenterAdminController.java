package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.*;
import coop.miriv.enology.identity.service.CenterAdminService;
import coop.miriv.enology.identity.service.CenterPurgeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/centers")
public class CenterAdminController {
    private final CenterAdminService service;
    private final CenterPurgeService purge;
    public CenterAdminController(CenterAdminService service, CenterPurgeService purge) { this.service = service; this.purge = purge; }
    @GetMapping public List<CenterOption> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CenterOption create(@Valid @RequestBody CenterAdminRequest r) { return service.create(r); }
    @PutMapping("/{code}") public CenterOption update(@PathVariable String code, @Valid @RequestBody CenterAdminRequest r) { return service.update(code, r); }
    @GetMapping("/{code}/impact") public CenterImpactResponse impact(@PathVariable String code) { return purge.impact(code); }
    /** Without cascade only an empty center can go; with cascade=true (SUPER_ADMIN) everything in it is deleted. */
    @DeleteMapping("/{code}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String code,
            @RequestParam(defaultValue = "false") boolean cascade, @RequestParam(required = false) String confirm) {
        if (cascade) purge.purge(code, confirm);
        else service.delete(code);
    }
}