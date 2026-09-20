package coop.miriv.enology.laboratory.web;

import coop.miriv.enology.laboratory.dto.ImportDto.ImportRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.ImportResponse;
import coop.miriv.enology.laboratory.dto.ImportDto.TemplateRequest;
import coop.miriv.enology.laboratory.dto.ImportDto.TemplateResponse;
import coop.miriv.enology.laboratory.service.AnalysisImportService;
import coop.miriv.enology.laboratory.service.ImportTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spreadsheet import of analyser readings: dry-run first, then create the samples and results. */
@RestController
@RequestMapping("/api/laboratory/imports/analyses")
public class AnalysisImportController {

    private final AnalysisImportService service;
    private final ImportTemplateService templates;

    public AnalysisImportController(AnalysisImportService service, ImportTemplateService templates) {
        this.service = service;
        this.templates = templates;
    }

    /** Same checks as the import, without writing: feeds the live warnings of the paste grid. */
    @PostMapping("/preview")
    public ImportResponse preview(@Valid @RequestBody ImportRequest request) { return service.preview(request); }

    @PostMapping
    public ImportResponse execute(@Valid @RequestBody ImportRequest request) { return service.execute(request); }

    /** Saved column matchings of the centre, so a recurring sheet maps itself. */
    @GetMapping("/templates")
    public List<TemplateResponse> templates() { return templates.list(); }

    @PutMapping("/templates")
    public TemplateResponse saveTemplate(@Valid @RequestBody TemplateRequest request) { return templates.save(request); }

    @DeleteMapping("/templates/{name}")
    public void deleteTemplate(@PathVariable String name) { templates.delete(name); }
}
