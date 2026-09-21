package coop.miriv.enology.laboratory.web;

import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelCreateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelUpdateRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.PanelView;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterRequest;
import coop.miriv.enology.laboratory.dto.PanelAdminDto.ParameterView;
import coop.miriv.enology.laboratory.service.PanelAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parameters and analysis templates. Reads are open to any signed-in user (the sample form needs them);
 * writes require LAB_CATALOG_MANAGE through PermissionRules.
 */
@RestController
@RequestMapping("/api/catalogs")
public class PanelAdminController {

    private final PanelAdminService service;

    public PanelAdminController(PanelAdminService service) { this.service = service; }

    @GetMapping("/parameters")
    public List<ParameterView> parameters() { return service.parameters(); }

    @PostMapping("/parameters")
    @ResponseStatus(HttpStatus.CREATED)
    public ParameterView createParameter(@Valid @RequestBody ParameterRequest request) { return service.createParameter(request); }

    @PutMapping("/parameters/{code}")
    public ParameterView updateParameter(@PathVariable String code, @Valid @RequestBody ParameterRequest request) {
        return service.updateParameter(code, request);
    }

    /** All templates, or only the active ones a category offers (default first) when `category` is given. */
    @GetMapping("/panels")
    public List<PanelView> panels(@RequestParam(required = false) String category) {
        return category == null ? service.panels() : service.panelsForCategory(category);
    }

    @PostMapping("/panels")
    @ResponseStatus(HttpStatus.CREATED)
    public PanelView createPanel(@Valid @RequestBody PanelCreateRequest request) { return service.createPanel(request); }

    @PutMapping("/panels/{code}")
    public PanelView updatePanel(@PathVariable String code, @Valid @RequestBody PanelUpdateRequest request) {
        return service.updatePanel(code, request);
    }
}
