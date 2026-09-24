package coop.miriv.enology.report.web;

import coop.miriv.enology.common.dto.PageResponse;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.report.dto.ReportDto.CreateReportRequest;
import coop.miriv.enology.report.dto.ReportDto.JobView;
import coop.miriv.enology.report.dto.ReportDto.Options;
import coop.miriv.enology.report.dto.ReportDto.PhaseOrderRequest;
import coop.miriv.enology.report.dto.ReportDto.PhaseRequest;
import coop.miriv.enology.report.dto.ReportDto.PhaseView;
import coop.miriv.enology.report.service.ReportPhaseService;
import coop.miriv.enology.report.service.ReportPhaseService.CurrentPhase;
import coop.miriv.enology.report.service.ReportService;
import coop.miriv.enology.report.service.ReportService.ReportFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports (UI23): issue a report (REPORT_EXPORT), list the center's reports, download their PDF / .xlsx
 * (REPORT_EXPORT), and the report phases edited from Administración (LAB_CATALOG_MANAGE).
 */
@RestController
public class ReportController {

    private final ReportService reports;
    private final ReportPhaseService phases;
    private final CurrentUserContext context;

    public ReportController(ReportService reports, ReportPhaseService phases, CurrentUserContext context) {
        this.reports = reports;
        this.phases = phases;
        this.context = context;
    }

    @GetMapping("/api/reports/options")
    public Options options() { return reports.options(); }

    @GetMapping("/api/reports")
    public PageResponse<JobView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reports.list(page, size);
    }

    @GetMapping("/api/reports/{code}")
    public JobView get(@PathVariable String code) { return reports.get(code); }

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(@Valid @RequestBody CreateReportRequest request) { return reports.create(request); }

    @GetMapping("/api/reports/{code}/file")
    public ResponseEntity<byte[]> file(@PathVariable String code, @RequestParam(defaultValue = "pdf") String format) {
        ReportFile file = reports.file(code, format);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .body(file.content());
    }

    // ------------------------------------------------------------------ report phases

    @GetMapping("/api/report-phases")
    public List<PhaseView> phases() { return phases.list(); }

    /** Phase a content is in now (manual when set by hand); {@code phase} is null when nothing matches. */
    @GetMapping("/api/report-phases/current")
    public CurrentPhase currentPhase(@RequestParam String content) {
        return phases.current(context.centerId(), content);
    }

    /** Sets the phase of a content by hand; a null {@code phaseId} goes back to the automatic phase. */
    @PutMapping("/api/report-phases/current")
    public CurrentPhase setCurrentPhase(@RequestParam String content, @Valid @RequestBody SetPhaseRequest request) {
        return phases.setPhase(context.centerId(), content, request.phaseId(), request.categoryCode(), request.reason(), context.userId());
    }

    /** {@code categoryCode} reclassifies the content in the same step when the new phase needs another category. */
    public record SetPhaseRequest(UUID phaseId, @Size(max = 40) String categoryCode, @Size(max = 500) String reason) {}

    @PostMapping("/api/admin/report-phases")
    @ResponseStatus(HttpStatus.CREATED)
    public PhaseView createPhase(@Valid @RequestBody PhaseRequest request) { return phases.create(request); }

    @PutMapping("/api/admin/report-phases/order")
    public List<PhaseView> reorder(@Valid @RequestBody PhaseOrderRequest request) { return phases.reorder(request.ids()); }

    @PutMapping("/api/admin/report-phases/{id}")
    public PhaseView updatePhase(@PathVariable UUID id, @Valid @RequestBody PhaseRequest request) { return phases.update(id, request); }

    @DeleteMapping("/api/admin/report-phases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhase(@PathVariable UUID id) { phases.delete(id); }
}
