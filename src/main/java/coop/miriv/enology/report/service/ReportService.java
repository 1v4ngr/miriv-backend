package coop.miriv.enology.report.service;

import coop.miriv.enology.audit.AuditService;
import coop.miriv.enology.common.CodeGenerator;
import coop.miriv.enology.common.dto.PageResponse;
import coop.miriv.enology.common.exception.BusinessRuleException;
import coop.miriv.enology.common.exception.NotFoundException;
import coop.miriv.enology.identity.service.CurrentUserContext;
import coop.miriv.enology.identity.service.CurrentUserContext.ZoneFilter;
import coop.miriv.enology.report.dto.ReportDto.CreateReportRequest;
import coop.miriv.enology.report.dto.ReportDto.Filters;
import coop.miriv.enology.report.dto.ReportDto.JobView;
import coop.miriv.enology.report.dto.ReportDto.Option;
import coop.miriv.enology.report.dto.ReportDto.Options;
import coop.miriv.enology.report.model.CellarReport;
import coop.miriv.enology.report.render.ReportPdfRenderer;
import coop.miriv.enology.report.render.ReportXlsxRenderer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues reports (RF-INF-01). A report is registered as PREPARING, its data is gathered once and rendered as a
 * PDF and an .xlsx, and both files are stored with the job, which becomes AVAILABLE — or FAILED with the reason,
 * so a failed attempt stays visible. Stored files are never regenerated: corrected data needs a new report.
 */
@Service
public class ReportService {

    public static final String CELLAR_STATUS = "CELLAR_STATUS";
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final int MAX_PAGE_SIZE = 100;

    public record ReportFile(String name, String contentType, byte[] content) {}

    private final JdbcTemplate jdbc;
    private final CurrentUserContext context;
    private final CellarReportBuilder builder;
    private final ReportPhaseService phases;
    private final ReportPdfRenderer pdf;
    private final ReportXlsxRenderer xlsx;
    private final CodeGenerator codes;
    private final AuditService audit;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final ZoneId zone;

    public ReportService(JdbcTemplate jdbc, CurrentUserContext context, CellarReportBuilder builder, ReportPhaseService phases,
                         ReportPdfRenderer pdf, ReportXlsxRenderer xlsx, CodeGenerator codes, AuditService audit, JsonMapper json,
                         PlatformTransactionManager transactions, @Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.context = context;
        this.builder = builder;
        this.phases = phases;
        this.pdf = pdf;
        this.xlsx = xlsx;
        this.codes = codes;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.zone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public Options options() {
        ZoneFilter zones = context.readZoneFilter("d");
        List<Object> args = new ArrayList<>();
        args.add(context.centerId());
        args.addAll(zones.zoneIds());
        List<Option> zoneOptions = jdbc.query("select distinct z.code, z.name from zone z join deposit d on d.zone_id = z.id "
                + "where d.center_id = ?" + zones.sql() + " order by z.name",
            (rs, n) -> new Option(rs.getString(1), rs.getString(2)), args.toArray());
        List<Option> categories = jdbc.query("select code, name from internal_category where active order by name",
            (rs, n) -> new Option(rs.getString(1), rs.getString(2)));
        return new Options(zoneOptions, builder.depositOptions(), categories, phases.list());
    }

    /** Issues a report now. Not transactional as a whole: a failure is stored on the job instead of rolled back. */
    public JobView create(CreateReportRequest request) {
        String type = request.type().trim().toUpperCase(Locale.ROOT);
        if (!CELLAR_STATUS.equals(type)) throw new BusinessRuleException("Tipo de informe no soportado: " + request.type());
        Filters filters = request.filters() == null ? Filters.empty() : request.filters();
        if (filters.from() != null && filters.to() != null && filters.from().isAfter(filters.to())) {
            throw new BusinessRuleException("La fecha inicial debe ser anterior o igual a la final.");
        }
        String title = request.title() == null || request.title().isBlank() ? "Estado de bodega" : request.title().trim();
        UUID centerId = context.centerId();
        UUID author = context.userId();

        UUID id = UUID.randomUUID();
        String code = tx.execute(status -> {
            String next = codes.next("INF", LocalDate.now(zone).getYear());
            jdbc.update("insert into report_job(id, code, center_id, type, title, filters, include_provisional, status, author_id) "
                    + "values (?, ?, ?, ?, ?, cast(? as jsonb), ?, 'PREPARING', ?)",
                id, next, centerId, type, title, json.writeValueAsString(filters), request.includeProvisional(), author);
            return next;
        });

        try {
            CellarReport report = builder.build(code, title, filters, request.includeProvisional());
            byte[] pdfBytes = pdf.render(report);
            byte[] xlsxBytes = xlsx.render(report);
            String base = fileBase(code, title, report.meta().generatedAt());
            tx.executeWithoutResult(status -> {
                jdbc.update("update report_job set status = 'AVAILABLE', record_count = ?, deposit_count = ?, pdf_name = ?, "
                        + "pdf_content = ?, xlsx_name = ?, xlsx_content = ?, finished_at = now() where id = ?",
                    report.rows().size(), report.deposits().size(), base + ".pdf", pdfBytes, base + ".xlsx", xlsxBytes, id);
                audit.record("report_job", id, "REPORT_ISSUED", title + " · " + report.meta().scope());
            });
        } catch (RuntimeException e) {
            log.warn("Report {} failed", code, e);
            String message = e instanceof BusinessRuleException || e instanceof NotFoundException
                ? e.getMessage() : "No se ha podido generar el informe (" + e.getClass().getSimpleName() + ").";
            tx.executeWithoutResult(status -> jdbc.update("update report_job set status = 'FAILED', error = ?, finished_at = now() "
                + "where id = ?", truncate(message, 1000), id));
        }
        return get(code);
    }

    @Transactional(readOnly = true)
    public PageResponse<JobView> list(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        UUID centerId = context.centerId();
        Long total = jdbc.queryForObject("select count(*) from report_job where center_id = ?", Long.class, centerId);
        List<JobView> items = jdbc.query(VIEW_SQL + " where j.center_id = ? order by j.created_at desc limit ? offset ?",
            (rs, n) -> view(rs), centerId, safeSize, safePage * safeSize);
        return PageResponse.of(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public JobView get(String code) {
        return jdbc.query(VIEW_SQL + " where j.center_id = ? and j.code = ?", (rs, n) -> view(rs), context.centerId(), code)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Informe no encontrado."));
    }

    @Transactional(readOnly = true)
    public ReportFile file(String code, String format) {
        String kind = format == null ? "pdf" : format.trim().toLowerCase(Locale.ROOT);
        if (!kind.equals("pdf") && !kind.equals("xlsx")) throw new BusinessRuleException("Formato no soportado: " + format);
        List<ReportFile> files = jdbc.query("select " + kind + "_name, " + kind + "_content from report_job "
                + "where center_id = ? and code = ? and status = 'AVAILABLE'",
            (rs, n) -> new ReportFile(rs.getString(1), kind.equals("pdf") ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", rs.getBytes(2)),
            context.centerId(), code);
        if (files.isEmpty() || files.getFirst().content() == null) throw new NotFoundException("Fichero de informe no disponible.");
        return files.getFirst();
    }

    private static final String VIEW_SQL = """
        select j.code, j.type, j.title, j.filters::text filters, j.include_provisional, j.status, j.error, j.record_count,
               j.deposit_count, u.full_name author, j.created_at, j.finished_at,
               j.pdf_content is not null has_pdf, j.xlsx_content is not null has_xlsx
          from report_job j join app_user u on u.id = j.author_id
        """;

    private JobView view(ResultSet rs) throws SQLException {
        Filters filters = json.readValue(rs.getString("filters"), Filters.class);
        return new JobView(rs.getString("code"), rs.getString("type"), rs.getString("title"), filters, scope(filters),
            rs.getBoolean("include_provisional"), rs.getString("status"), rs.getString("error"),
            (Integer) rs.getObject("record_count"), (Integer) rs.getObject("deposit_count"), rs.getString("author"),
            instant(rs, "created_at"), instant(rs, "finished_at"), rs.getBoolean("has_pdf"), rs.getBoolean("has_xlsx"));
    }

    /** Short, human description of the filters for the job list. */
    private String scope(Filters filters) {
        List<String> parts = new ArrayList<>();
        if (!filters.zonesOrEmpty().isEmpty()) parts.add("Zonas " + String.join(", ", filters.zonesOrEmpty()));
        if (!filters.depositsOrEmpty().isEmpty()) parts.add("Depósitos " + String.join(", ", filters.depositsOrEmpty()));
        if (!filters.phasesOrEmpty().isEmpty()) {
            Map<String, String> names = new java.util.HashMap<>();
            phases.all().forEach(phase -> names.put(phase.code(), phase.name()));
            names.put("NONE", "Sin fase");
            parts.add("Fases " + String.join(", ", filters.phasesOrEmpty().stream().map(code -> names.getOrDefault(code, code)).toList()));
        }
        if (!filters.categoriesOrEmpty().isEmpty()) parts.add("Categorías " + String.join(", ", filters.categoriesOrEmpty()));
        parts.add(switch (filters.periodOrDefault()) {
            case Filters.CONTENT_START -> "desde inicio del contenido";
            case Filters.RANGE -> (filters.from() == null ? "…" : filters.from().toString()) + " → " + (filters.to() == null ? "hoy" : filters.to().toString());
            default -> "desde entrada en depósito";
        });
        if (parts.size() == 1) parts.addFirst("Toda la bodega");
        return String.join(" · ", parts);
    }

    private String fileBase(String code, String title, Instant at) {
        String slug = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "informe";
        if (slug.length() > 60) slug = slug.substring(0, 60);
        return slug + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zone).format(at) + "-" + code;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
