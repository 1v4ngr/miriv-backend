package coop.miriv.enology.identity.web;

import coop.miriv.enology.identity.dto.*;
import coop.miriv.enology.identity.service.ZoneAdminService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/zones")
public class ZoneAdminController {
    private final ZoneAdminService service;
    public ZoneAdminController(ZoneAdminService service) { this.service = service; }
    @GetMapping public List<Map<String, String>> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String, String> create(@Valid @RequestBody ZoneAdminRequest r) { return service.create(r); }
    @PutMapping("/{code}") public void update(@PathVariable String code, @Valid @RequestBody ZoneAdminRequest r) { service.update(code, r); }
    @DeleteMapping("/{code}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String code) { service.delete(code); }
}