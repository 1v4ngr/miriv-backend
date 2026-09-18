package coop.miriv.enology.laboratory.web;

import coop.miriv.enology.laboratory.dto.CorrectionRequest;
import coop.miriv.enology.laboratory.dto.NewSampleRequest;
import coop.miriv.enology.laboratory.dto.NoteRequest;
import coop.miriv.enology.laboratory.dto.ReasonRequest;
import coop.miriv.enology.laboratory.dto.ResultsRequest;
import coop.miriv.enology.laboratory.dto.SampleResponse;
import coop.miriv.enology.laboratory.service.LaboratoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/laboratory/samples")
public class LaboratoryController {

    private final LaboratoryService service;

    public LaboratoryController(LaboratoryService service) { this.service = service; }

    @GetMapping
    public List<SampleResponse> list() { return service.list(); }

    @GetMapping("/{code}")
    public SampleResponse get(@PathVariable String code) { return service.get(code); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SampleResponse create(@Valid @RequestBody NewSampleRequest request) { return service.create(request); }

    @PutMapping("/{code}/results")
    public SampleResponse saveResults(@PathVariable String code, @Valid @RequestBody ResultsRequest request) {
        return service.saveResults(code, request);
    }

    @PostMapping("/{code}/validate")
    public SampleResponse validate(@PathVariable String code, @RequestBody NoteRequest request) {
        return service.validate(code, request.note());
    }

    @PostMapping("/{code}/results/{parameter}/correction")
    public SampleResponse correct(@PathVariable String code, @PathVariable String parameter,
                                  @Valid @RequestBody CorrectionRequest request) {
        return service.correct(code, parameter, request);
    }

    @PostMapping("/{code}/invalidate")
    public SampleResponse invalidate(@PathVariable String code, @Valid @RequestBody ReasonRequest request) {
        return service.invalidate(code, request.reason());
    }
}
