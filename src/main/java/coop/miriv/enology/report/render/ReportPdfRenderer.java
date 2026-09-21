package coop.miriv.enology.report.render;

import static coop.miriv.enology.report.render.Html.escape;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import coop.miriv.enology.report.model.CellarReport;
import coop.miriv.enology.report.model.CellarReport.Alert;
import coop.miriv.enology.report.model.CellarReport.Block;
import coop.miriv.enology.report.model.CellarReport.Cell;
import coop.miriv.enology.report.model.CellarReport.DepositReport;
import coop.miriv.enology.report.model.CellarReport.EmptyDeposit;
import coop.miriv.enology.report.model.CellarReport.Event;
import coop.miriv.enology.report.model.CellarReport.Latest;
import coop.miriv.enology.report.model.CellarReport.Parameter;
import coop.miriv.enology.report.model.CellarReport.PhaseCount;
import coop.miriv.enology.report.model.CellarReport.PhaseRef;
import coop.miriv.enology.report.model.CellarReport.Range;
import coop.miriv.enology.report.model.CellarReport.Segment;
import coop.miriv.enology.report.model.CellarReport.Series;
import coop.miriv.enology.report.model.CellarReport.TableRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link CellarReport} as a printable A4 PDF: a cover with the cellar summary grouped by phase, then
 * one section per deposit with its phase timeline, latest values, charts and tables for each phase it went
 * through (current phase first) and its events. Built as XHTML with inline SVG and rendered by openhtmltopdf.
 */
@Component
public class ReportPdfRenderer {

    private static final double CONTENT_WIDTH = 686;
    private static final double CHART_WIDTH = 322;
    private static final int TABLE_COLUMNS = 7;
    private static final int MAX_EVENTS = 40;
    private static final String PLUM = "#6d4656";

    private record Font(String file, String family, int weight) {}

    private static final List<Font> FONTS = List.of(
        new Font("PlusJakartaSans-Regular.ttf", "Jakarta", 400),
        new Font("PlusJakartaSans-Medium.ttf", "Jakarta", 500),
        new Font("PlusJakartaSans-SemiBold.ttf", "Jakarta", 600),
        new Font("PlusJakartaSans-Bold.ttf", "Jakarta", 700),
        new Font("CormorantGaramond-SemiBold.ttf", "Cormorant", 600),
        new Font("IBMPlexMono-Regular.ttf", "PlexMono", 400),
        new Font("IBMPlexMono-Medium.ttf", "PlexMono", 500));

    public byte[] render(CellarReport report) {
        String html = html(report);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            for (Font font : FONTS) {
                builder.useFont(() -> resource(font.file()), font.family(), font.weight(), FontStyle.NORMAL, true);
            }
            builder.withProducer("MIRIV");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String file) {
        InputStream stream = ReportPdfRenderer.class.getResourceAsStream("/report/fonts/" + file);
        if (stream == null) throw new IllegalStateException("Falta la fuente del informe: " + file);
        return stream;
    }

    // ------------------------------------------------------------------ document

    String html(CellarReport report) {
        ZoneId zone = ZoneId.of(report.meta().timezone());
        StringBuilder h = new StringBuilder(64_000);
        h.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\"/>");
        h.append("<title>").append(escape(report.meta().title())).append("</title>");
        h.append("<meta name=\"author\" content=\"").append(escape(report.meta().author())).append("\"/>");
        h.append("<meta name=\"subject\" content=\"").append(escape(report.meta().centerName())).append("\"/>");
        h.append("<style>").append(css(report)).append("</style></head><body>");
        cover(h, report, zone);
        for (DepositReport deposit : report.deposits()) deposit(h, deposit, zone);
        appendix(h, report, zone);
        h.append("</body></html>");
        return h.toString();
    }

    private static String css(CellarReport report) {
        String footer = escape(report.meta().centerName() + " · " + report.meta().title()).replace("\"", "'");
        return """
            @page { size: A4; margin: 17mm 13mm 16mm 13mm;
              @top-left { content: "%s"; font-family: Jakarta; font-size: 7.5pt; color: #8a7f86; }
              @top-right { content: "%s"; font-family: PlexMono; font-size: 7.5pt; color: #8a7f86; }
              @bottom-left { content: "Generado con MIRIV · seguimiento enológico"; font-family: Jakarta; font-size: 7pt; color: #a99ea5; }
              @bottom-right { content: "Página " counter(page) " de " counter(pages); font-family: Jakarta; font-size: 7.5pt; color: #8a7f86; }
            }
            @page :first { margin-top: 12mm; @top-left { content: none; } @top-right { content: none; } }
            * { box-sizing: border-box; }
            body { font-family: Jakarta; font-size: 8.4pt; color: #2e262a; line-height: 1.38; margin: 0; }
            h1, h2, h3, h4 { margin: 0; font-weight: 600; }
            .display { font-family: Cormorant; font-weight: 600; }
            .mono { font-family: PlexMono; }
            .muted { color: #6f6169; }
            .soft { color: #8a7f86; }
            .small { font-size: 7.3pt; }
            .tiny { font-size: 6.6pt; }
            .right { text-align: right; }
            .center { text-align: center; }
            .nowrap { white-space: nowrap; }
            .page { page-break-before: always; }
            .keep { page-break-inside: avoid; }

            .hero { background-color: #6d4656; color: #fdfbfc; border-radius: 14px; padding: 16px 20px 18px 20px; }
            .hero td { vertical-align: middle; }
            .brand { font-size: 8pt; letter-spacing: 1.6px; font-weight: 700; color: #f3e7ee; }
            .hero h1 { font-family: Cormorant; font-size: 30pt; line-height: 1.05; color: #ffffff; margin-top: 8px; }
            .hero .sub { color: #eadfe4; font-size: 8.6pt; margin-top: 4px; }
            .hero .code { font-family: PlexMono; font-size: 8pt; color: #f3e7ee; }

            table { border-collapse: collapse; width: 100%%; }
            .grid { table-layout: fixed; }
            .grid > tbody > tr > td { vertical-align: top; }
            .gap td { padding: 0 4px; }
            .gap td:first-child { padding-left: 0; }
            .gap td:last-child { padding-right: 0; }

            .facts { margin-top: 12px; border: 1px solid #eadfe4; border-radius: 12px; }
            .facts td { padding: 8px 12px; border-right: 1px solid #eadfe4; vertical-align: top; }
            .facts td:last-child { border-right: none; }
            .label { font-size: 6.8pt; text-transform: uppercase; letter-spacing: 0.7px; color: #8a7f86; font-weight: 600; }
            .value { font-size: 9pt; font-weight: 600; margin-top: 2px; }

            .kpi { border: 1px solid #eadfe4; border-radius: 12px; padding: 10px 12px; background-color: #fbf8fa; }
            .kpi .num { font-size: 15pt; font-weight: 700; color: #452833; line-height: 1.1; margin-top: 3px; }
            .kpi .num.warn { color: #9a6d12; }
            .kpi .num.crit { color: #b3263f; }

            .section-title { font-size: 11.5pt; font-weight: 700; color: #452833; margin: 18px 0 8px 0; }
            .section-title .count { font-size: 8pt; font-weight: 600; color: #8a7f86; }

            .phasebar { border-radius: 6px; }
            .phase-chip { display: inline-block; padding: 2px 8px; border-radius: 9px; color: #ffffff; font-size: 7.4pt; font-weight: 600; }
            .dot { display: inline-block; width: 7px; height: 7px; border-radius: 4px; margin-right: 4px; }
            .dot.hollow { border: 1.3px solid #6d4656; background-color: #ffffff; }
            .tri { display: inline-block; width: 0; height: 0; border-left: 4px solid transparent; border-right: 4px solid transparent;
                   border-top: 7px solid #6d4656; margin-right: 4px; }
            .band { display: inline-block; width: 14px; height: 7px; margin-right: 4px; }
            .warnband { background-color: #fbefd8; border-top: 1px dashed #c0902a; }
            .critband { background-color: #f7e0e6; border-top: 1px dashed #b3263f; }

            .list { border: 1px solid #eadfe4; border-radius: 10px; }
            .list th { background-color: #fbf7f9; color: #6f6169; font-size: 6.9pt; font-weight: 600; text-align: left; padding: 5px 6px;
                       border-bottom: 1px solid #eadfe4; text-transform: uppercase; letter-spacing: 0.4px; }
            .list td { padding: 4.5px 6px; border-bottom: 1px solid #f1e9ed; vertical-align: top; }
            .list tr { page-break-inside: avoid; }
            .list tr.group td { background-color: #fdfbfc; font-weight: 700; color: #452833; padding-top: 7px; border-bottom: 1px solid #eadfe4; }
            .list tr:last-child td { border-bottom: none; }
            .list .num { text-align: right; font-family: PlexMono; font-size: 7.8pt; white-space: nowrap; }
            .list .prov { color: #8a7f86; }

            .st-OK { color: #2f7a4d; }
            .st-WARN { color: #9a6d12; font-weight: 600; }
            .st-CRIT { color: #b3263f; font-weight: 700; }
            .st-UNKNOWN { color: #6f6169; }
            .badge { display: inline-block; padding: 1px 7px; border-radius: 8px; font-size: 7pt; font-weight: 600; }
            .badge-OK { background-color: #dceadf; color: #1f5c3a; }
            .badge-WARN { background-color: #f8ebcc; color: #7a5410; }
            .badge-CRIT { background-color: #f7e0e6; color: #8e1f33; }
            .badge-UNKNOWN, .badge-NONE { background-color: #f1ecef; color: #6f6169; }

            .dep-head { border-bottom: 2px solid #6d4656; padding-bottom: 8px; }
            .dep-code { font-family: Cormorant; font-size: 26pt; line-height: 1; color: #452833; }
            .fill { background-color: #f3e7ee; height: 6px; border-radius: 3px; margin-top: 4px; }
            .fill div { background-color: #6d4656; height: 6px; border-radius: 3px; }

            .alert { border-radius: 10px; padding: 7px 10px; margin-top: 6px; }
            .alert-CRIT { background-color: #f7e0e6; color: #8e1f33; }
            .alert-WARN { background-color: #f8ebcc; color: #7a5410; }
            .alert-INFO { background-color: #e6eff3; color: #1f4f63; }

            .latest td.card { border: 1px solid #eadfe4; border-radius: 9px; padding: 6px 8px; }
            .latest .pname { font-size: 7pt; color: #6f6169; }
            .latest .pval { font-size: 11pt; font-weight: 500; font-family: PlexMono; }
            .latest .punit { font-size: 7pt; color: #8a7f86; }

            .block { margin-top: 16px; }
            .block-head { border-left: 5px solid #6d4656; padding: 4px 0 4px 9px; background-color: #fbf8fa; border-radius: 0 8px 8px 0; }
            .block-head h3 { font-size: 10.5pt; }
            .chart { border: 1px solid #eadfe4; border-radius: 10px; padding: 7px 8px 3px 8px; margin-bottom: 8px; page-break-inside: avoid; }
            .chart .ctitle { font-weight: 700; font-size: 8.4pt; }
            .chart .cmeta { font-size: 6.9pt; color: #8a7f86; }
            .legend { font-size: 6.9pt; color: #6f6169; margin-top: 4px; }
            .legend span { margin-right: 10px; }
            .note { font-size: 7pt; color: #8a7f86; margin-top: 4px; }
            .empty { border: 1px dashed #d9ccd2; border-radius: 10px; padding: 10px; color: #8a7f86; text-align: center; margin-top: 6px; }
            """.formatted(footer, escape(report.meta().code()));
    }

    // ------------------------------------------------------------------ cover

    private void cover(StringBuilder h, CellarReport report, ZoneId zone) {
        CellarReport.Meta meta = report.meta();
        h.append("<div class=\"hero\"><table><tr><td style=\"width:52px\">").append(logo(44)).append("</td><td>")
            .append("<div class=\"brand\">MIRIV · ").append(escape(meta.centerName().toUpperCase(java.util.Locale.ROOT))).append("</div>")
            .append("</td><td class=\"right\"><div class=\"code\">").append(escape(meta.code())).append("</div></td></tr></table>")
            .append("<h1>").append(escape(meta.title())).append("</h1>")
            .append("<div class=\"sub\">Emitido el ").append(escape(Html.longDateTime(meta.generatedAt(), zone)))
            .append(" por ").append(escape(meta.author())).append("</div></div>");

        // Facts about the report itself.
        int records = report.rows().size();
        h.append("<table class=\"facts grid\"><tr>")
            .append(fact("Periodo", periodText(meta, zone)))
            .append(fact("Resultados", meta.includeProvisional() ? "Validados y provisionales" : "Solo validados"))
            .append(fact("Registros analíticos", String.valueOf(records)))
            .append(fact("Zona horaria", meta.timezone()))
            .append("</tr><tr><td colspan=\"4\" style=\"border-top:1px solid #eadfe4\"><div class=\"label\">Alcance</div><div class=\"small\" style=\"margin-top:2px\">")
            .append(escape(meta.scope())).append("</div></td></tr></table>");

        // KPIs.
        List<DepositReport> deposits = report.deposits();
        BigDecimal volume = deposits.stream().map(DepositReport::volumeLiters).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long warn = deposits.stream().filter(d -> "WARN".equals(d.worstStatus())).count();
        long crit = deposits.stream().filter(d -> "CRIT".equals(d.worstStatus())).count();
        long stale = deposits.stream().filter(d -> d.lastSampleAt() == null
            || Duration.between(d.lastSampleAt(), meta.generatedAt()).toDays() > 7).count();
        long alerts = deposits.stream().mapToLong(d -> d.alerts().size()).sum();
        h.append("<table class=\"grid gap\" style=\"margin-top:12px\"><tr>")
            .append(kpi("Depósitos con contenido", String.valueOf(deposits.size()), ""))
            .append(kpi("Volumen", Html.number(volume.divide(BigDecimal.valueOf(100), 1, java.math.RoundingMode.HALF_UP),
                volume.compareTo(BigDecimal.valueOf(10_000)) >= 0 ? 0 : 1) + "\u00A0hL", ""))
            .append(kpi("En aviso", String.valueOf(warn), warn > 0 ? "warn" : ""))
            .append(kpi("En crítico", String.valueOf(crit), crit > 0 ? "crit" : ""))
            .append(kpi("Sin muestra > 7 días", String.valueOf(stale), stale > 0 ? "warn" : ""))
            .append(kpi("Avisos activos", String.valueOf(alerts), alerts > 0 ? "crit" : ""))
            .append("</tr></table>");

        // Distribution by phase.
        h.append("<div class=\"section-title\">Distribución por fase</div>");
        phaseDistribution(h, report);

        // Summary table grouped by phase.
        h.append("<div class=\"section-title\">Resumen de depósitos <span class=\"count\">· ").append(deposits.size())
            .append(" con contenido</span></div>");
        if (deposits.isEmpty()) {
            h.append("<div class=\"empty\">Ningún depósito con contenido encaja con el alcance elegido.</div>");
        } else {
            summaryTable(h, report, zone);
        }

        if (!report.emptyDeposits().isEmpty()) {
            h.append("<div class=\"section-title\">Depósitos sin contenido <span class=\"count\">· ")
                .append(report.emptyDeposits().size()).append("</span></div><div class=\"small muted\">");
            List<String> items = new ArrayList<>();
            for (EmptyDeposit empty : report.emptyDeposits()) {
                items.add("<b>" + escape(empty.deposit()) + "</b> " + escape(depositStatus(empty.status()))
                    + (empty.capacityLiters() == null ? "" : " (" + escape(Html.liters(empty.capacityLiters())) + ")"));
            }
            h.append(String.join(" · ", items)).append("</div>");
        }
    }

    private static String periodText(CellarReport.Meta meta, ZoneId zone) {
        return switch (meta.periodMode()) {
            case "CONTENT_START" -> "Desde el inicio de cada contenido hasta hoy";
            case "RANGE" -> (meta.from() == null ? "Inicio de cada contenido" : Html.date(meta.from(), zone)) + " – "
                + (meta.to() == null ? "hoy" : Html.date(meta.to().minusSeconds(1), zone));
            default -> "Desde la entrada en el depósito actual hasta hoy";
        };
    }

    private void phaseDistribution(StringBuilder h, CellarReport report) {
        int total = report.phaseCounts().stream().mapToInt(PhaseCount::deposits).sum();
        if (total > 0) {
            StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 16\" ")
                .append(SvgCharts.size(CONTENT_WIDTH, CONTENT_WIDTH * 16 / 1000)).append(">");
            svg.append("<rect x=\"0\" y=\"0\" width=\"1000\" height=\"16\" rx=\"8\" fill=\"#f3e7ee\"/>");
            double x = 0;
            for (PhaseCount count : report.phaseCounts()) {
                if (count.deposits() == 0) continue;
                double w = 1000.0 * count.deposits() / total;
                svg.append("<rect x=\"").append(SvgCharts.fmt(x)).append("\" y=\"0\" width=\"").append(SvgCharts.fmt(w))
                    .append("\" height=\"16\" fill=\"").append(count.phase().color()).append("\"/>");
                if (x > 0) svg.append("<line x1=\"").append(SvgCharts.fmt(x)).append("\" y1=\"0\" x2=\"").append(SvgCharts.fmt(x))
                    .append("\" y2=\"16\" stroke=\"#ffffff\" stroke-width=\"3\"/>");
                x += w;
            }
            svg.append("</svg>");
            h.append("<div>").append(svg).append("</div>");
        }
        h.append("<table class=\"list\" style=\"margin-top:8px\"><tr><th style=\"width:170px\">Fase</th><th class=\"right\">Depósitos</th>")
            .append("<th class=\"right\">Volumen</th><th class=\"right\">En aviso</th><th class=\"right\">En crítico</th>")
            .append("<th>Parámetros que se siguen</th></tr>");
        for (PhaseCount count : report.phaseCounts()) {
            h.append("<tr><td class=\"nowrap\"><span class=\"dot\" style=\"background-color:").append(count.phase().color())
                .append("\"></span><b>").append(escape(count.phase().name())).append("</b></td>")
                .append("<td class=\"num\">").append(count.deposits()).append("</td>")
                .append("<td class=\"num\">").append(escape(Html.liters(count.volumeLiters()))).append("</td>")
                .append("<td class=\"num").append(count.warn() > 0 ? " st-WARN" : "").append("\">").append(count.warn()).append("</td>")
                .append("<td class=\"num").append(count.crit() > 0 ? " st-CRIT" : "").append("\">").append(count.crit()).append("</td>")
                .append("<td class=\"small muted\">").append(escape(parameterNames(count.phase(), report))).append("</td></tr>");
        }
        h.append("</table>");
    }

    private static String parameterNames(PhaseRef phase, CellarReport report) {
        return phase.parameters().isEmpty() ? "Los que tengan datos" : report.parameterNames(phase.parameters());
    }

    private void summaryTable(StringBuilder h, CellarReport report, ZoneId zone) {
        h.append("<table class=\"list\"><thead><tr><th>Depósito</th><th>Contenido</th><th>Categoría</th>")
            .append("<th class=\"right\">Volumen</th><th>FA / FML</th><th>En fase</th><th>Última muestra</th>")
            .append("<th>Valores clave</th><th>Estado</th></tr></thead><tbody>");
        String currentPhase = "__";
        for (DepositReport d : report.deposits()) {
            String key = String.valueOf(d.phase().code());
            if (!key.equals(currentPhase)) {
                currentPhase = key;
                h.append("<tr class=\"group\"><td colspan=\"9\"><span class=\"dot\" style=\"background-color:")
                    .append(d.phase().color()).append("\"></span>").append(escape(d.phase().name())).append("</td></tr>");
            }
            long daysInPhase = d.phaseSince() == null ? 0 : Duration.between(d.phaseSince(), report.meta().generatedAt()).toDays();
            h.append("<tr><td class=\"nowrap\"><b>").append(escape(d.deposit())).append("</b>")
                .append(d.zone() == null ? "" : "<div class=\"tiny soft\">" + escape(d.zone()) + "</div>").append("</td>")
                .append("<td class=\"mono small nowrap\">").append(escape(d.content())).append("<div class=\"tiny soft\">")
                .append(escape(d.lot())).append("</div></td>")
                .append("<td>").append(escape(d.categoryName() == null ? "—" : d.categoryName())).append("</td>")
                .append("<td class=\"num\">").append(escape(Html.liters(d.volumeLiters())))
                .append(d.fillPercent() == null ? "" : "<div class=\"tiny soft\">" + d.fillPercent() + " %</div>").append("</td>")
                .append("<td class=\"small\">").append(escape(Html.state(d.alcoholicState()))).append("<div class=\"tiny soft\">")
                .append(escape(Html.state(d.malolacticState()))).append("</div></td>")
                .append("<td class=\"small nowrap\">").append(escape(Html.days(daysInPhase))).append("</td>")
                .append("<td class=\"small nowrap\">").append(escape(Html.dateTime(d.lastSampleAt(), zone))).append("</td>")
                .append("<td class=\"small\">").append(keyValues(d)).append("</td>")
                .append("<td>").append(badge(d.worstStatus())).append("</td></tr>");
        }
        h.append("</tbody></table>");
    }

    /** Latest value of the first parameters of the current phase. */
    private static String keyValues(DepositReport d) {
        List<String> codes = d.phase().parameters().isEmpty()
            ? d.latest().stream().map(item -> item.parameter().code()).toList() : d.phase().parameters();
        List<String> parts = new ArrayList<>();
        for (String code : codes) {
            Latest latest = d.latest().stream().filter(item -> item.parameter().code().equals(code)).findFirst().orElse(null);
            if (latest == null) continue;
            parts.add("<span class=\"nowrap\">" + escape(latest.parameter().name()) + " <span style=\"font-weight:500\" class=\"mono st-" + latest.row().status() + "\">"
                + escape(Html.result(latest.row().value(), latest.row().qualifier(), latest.row().limit(), latest.parameter().decimals()))
                + "</span></span>");
            if (parts.size() == 3) break;
        }
        return parts.isEmpty() ? "<span class=\"soft\">Sin datos</span>" : String.join("<br/>", parts);
    }

    // ------------------------------------------------------------------ deposit section

    private void deposit(StringBuilder h, DepositReport d, ZoneId zone) {
        h.append("<div class=\"page\">");
        // Header.
        h.append("<table class=\"dep-head\"><tr><td style=\"vertical-align:bottom\">")
            .append("<div class=\"label\">Depósito").append(d.zone() == null ? "" : " · " + escape(d.zone())).append("</div>")
            .append("<div class=\"dep-code\">").append(escape(d.deposit())).append("</div>")
            .append(d.depositName() == null || d.depositName().isBlank() ? "" : "<div class=\"muted small\">" + escape(d.depositName()) + "</div>")
            .append("</td><td class=\"right\" style=\"vertical-align:bottom;width:260px\">")
            .append("<span class=\"phase-chip\" style=\"background-color:").append(d.phase().color()).append("\">")
            .append(escape(d.phase().name())).append("</span>")
            .append(d.phaseSince() == null ? "" : "<div class=\"tiny soft\" style=\"margin-top:3px\">en esta fase desde el "
                + escape(Html.date(d.phaseSince(), zone)) + "</div>")
            .append("</td></tr></table>");

        // Facts.
        h.append("<table class=\"facts grid\"><tr>")
            .append(fact("Contenido", "<span class=\"mono\">" + escape(d.content()) + "</span>", true))
            .append(fact("Lote", "<span class=\"mono\">" + escape(d.lot()) + "</span>", true))
            .append(fact("Categoría", escape(d.categoryName() == null ? "—" : d.categoryName()), true))
            .append(fillFact(d))
            .append("</tr><tr style=\"border-top:1px solid #eadfe4\">")
            .append(fact("Fermentación alcohólica", escape(Html.state(d.alcoholicState())), true))
            .append(fact("Fermentación maloláctica", escape(Html.state(d.malolacticState())), true))
            .append(fact("Entrada en el depósito", escape(Html.dateTime(d.enteredDeposit(), zone)), true))
            .append(fact("Plan de elaboración", escape(d.plan() == null ? "Sin plan asignado" : d.plan()), true))
            .append("</tr><tr style=\"border-top:1px solid #eadfe4\">")
            .append(fact("Periodo del informe", escape(Html.shortYearDate(d.from(), zone) + " – " + Html.shortYearDate(d.to(), zone)), true))
            .append(fact("Muestras en el periodo", String.valueOf(d.sampleCount()), true))
            .append(fact("Última muestra", escape(Html.dateTime(d.lastSampleAt(), zone)), true))
            .append(fact("Estado analítico", badge(d.worstStatus()), true))
            .append("</tr></table>");

        // Alerts that hold now.
        for (Alert alert : d.alerts()) {
            h.append("<div class=\"alert alert-").append(escape(alert.severity())).append("\"><b>").append(escape(alert.rule()))
                .append("</b> · ").append(escape(alert.detail())).append(" <span class=\"tiny\">(desde ")
                .append(escape(Html.dateTime(alert.since(), zone))).append(")</span></div>");
        }

        // Phase timeline.
        h.append("<div class=\"section-title\">Fases en el periodo</div>");
        if (!d.segments().isEmpty()) {
            h.append("<div>").append(SvgCharts.timeline(d.segments(), d.from(), d.to(), d.events(), CONTENT_WIDTH)).append("</div>");
            h.append("<table class=\"grid\" style=\"margin-top:3px\"><tr><td class=\"tiny soft\">").append(escape(Html.date(d.from(), zone)))
                .append("</td><td class=\"tiny soft right\">").append(escape(Html.date(d.to(), zone))).append("</td></tr></table>");
            h.append("<table class=\"list\" style=\"margin-top:6px\"><tr><th>Fase</th><th>Desde</th><th>Hasta</th><th class=\"right\">Duración</th>")
                .append("<th>Categoría</th><th>FA</th><th>FML</th></tr>");
            for (Segment segment : d.segments()) {
                long days = Duration.between(segment.from(), segment.to()).toDays();
                h.append("<tr><td class=\"nowrap\"><span class=\"dot\" style=\"background-color:").append(segment.phase().color())
                    .append("\"></span><b>").append(escape(segment.phase().name())).append("</b></td>")
                    .append("<td class=\"nowrap\">").append(escape(Html.dateTime(segment.from(), zone))).append("</td>")
                    .append("<td class=\"nowrap\">").append(escape(segment.to().equals(d.to()) ? "hoy" : Html.dateTime(segment.to(), zone))).append("</td>")
                    .append("<td class=\"num\">").append(escape(Html.days(days))).append("</td>")
                    .append("<td>").append(escape(segment.categoryName() == null ? "—" : segment.categoryName())).append("</td>")
                    .append("<td>").append(escape(Html.state(segment.alcoholicState()))).append("</td>")
                    .append("<td>").append(escape(Html.state(segment.malolacticState()))).append("</td></tr>");
            }
            h.append("</table>");
        }

        // Latest values.
        h.append("<div class=\"section-title\">Últimos valores <span class=\"count\">· en el periodo</span></div>");
        if (d.latest().isEmpty()) {
            h.append("<div class=\"empty\">Sin resultados analíticos en el periodo").append(" del informe.</div>");
        } else {
            latestGrid(h, d);
        }

        // One block per phase segment, current phase first.
        List<Block> blocks = new ArrayList<>(d.blocks());
        java.util.Collections.reverse(blocks);
        boolean first = true;
        for (Block block : blocks) {
            block(h, d, block, zone, first);
            first = false;
        }

        // Events.
        if (!d.events().isEmpty()) {
            h.append("<div class=\"section-title\">Movimientos y revisiones <span class=\"count\">· ").append(d.events().size()).append("</span></div>");
            h.append("<table class=\"list\"><tr><th style=\"width:92px\">Fecha</th><th style=\"width:92px\">Tipo</th><th>Descripción</th><th>Detalle</th></tr>");
            List<Event> events = d.events().size() > MAX_EVENTS ? d.events().subList(d.events().size() - MAX_EVENTS, d.events().size()) : d.events();
            for (Event event : events) {
                h.append("<tr><td class=\"nowrap\">").append(escape(Html.dateTime(event.at(), zone))).append("</td>")
                    .append("<td class=\"nowrap\"><span class=\"dot\" style=\"background-color:").append(SvgCharts.eventColor(event.type()))
                    .append("\"></span>").append(escape(Html.event(event.type()))).append("</td>")
                    .append("<td>").append(escape(event.label())).append("</td>")
                    .append("<td class=\"small muted\">").append(escape(event.detail())).append("</td></tr>");
            }
            h.append("</table>");
            if (d.events().size() > MAX_EVENTS) {
                h.append("<div class=\"note\">Se muestran los ").append(MAX_EVENTS).append(" más recientes; el Excel los incluye todos.</div>");
            }
        }
        h.append("</div>");
    }

    private static String fillFact(DepositReport d) {
        StringBuilder value = new StringBuilder(escape(Html.liters(d.volumeLiters())));
        if (d.capacityLiters() != null) value.append(" <span class=\"soft small\">/ ").append(escape(Html.liters(d.capacityLiters()))).append("</span>");
        if (d.fillPercent() != null) {
            int pct = Math.max(0, Math.min(100, d.fillPercent()));
            value.append("<div class=\"fill\"><div style=\"width:").append(pct).append("%\"></div></div>");
        }
        return fact("Volumen · llenado " + (d.fillPercent() == null ? "" : d.fillPercent() + " %"), value.toString(), true);
    }

    private void latestGrid(StringBuilder h, DepositReport d) {
        int perRow = 5;
        h.append("<table class=\"latest grid\" style=\"border-collapse:separate;border-spacing:5px;margin:0 -5px\">");
        List<Latest> items = d.latest();
        for (int i = 0; i < items.size(); i += perRow) {
            h.append("<tr>");
            for (int j = i; j < i + perRow; j++) {
                if (j >= items.size()) { h.append("<td></td>"); continue; }
                Latest item = items.get(j);
                String status = item.row().status();
                String color = SvgCharts.statusColor(status);
                h.append("<td class=\"card\"").append(color == null ? "" : " style=\"border-left:3px solid " + color + "\"").append(">")
                    .append("<div class=\"pname\">").append(escape(item.parameter().name())).append("</div>")
                    .append("<div><span class=\"pval st-").append(status).append("\">")
                    .append(escape(Html.result(item.row().value(), item.row().qualifier(), item.row().limit(), item.parameter().decimals())))
                    .append("</span> <span class=\"punit\">").append(escape(item.parameter().unit() == null ? "" : item.parameter().unit())).append("</span>")
                    .append(item.row().validated() ? "" : " <span class=\"tiny soft\">prov.</span>").append("</div>")
                    .append("<div class=\"tiny soft\">hace ").append(escape(Html.days(item.daysAgo()))).append("</div></td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");
    }

    private void block(StringBuilder h, DepositReport d, Block block, ZoneId zone, boolean current) {
        Segment segment = block.segment();
        long days = Duration.between(segment.from(), segment.to()).toDays();
        h.append("<div class=\"block\">");
        h.append("<div class=\"block-head keep\" style=\"border-left-color:").append(segment.phase().color()).append("\">")
            .append("<div class=\"label\">").append(current ? "Fase actual" : "Fase anterior").append("</div>")
            .append("<h3>").append(escape(segment.phase().name())).append("</h3>")
            .append("<div class=\"small muted\">").append(escape(Html.date(segment.from(), zone))).append(" – ")
            .append(escape(current ? "hoy" : Html.date(segment.to(), zone))).append(" · ").append(escape(Html.days(days)))
            .append(segment.categoryName() == null ? "" : " · " + escape(segment.categoryName()))
            .append(" · FA ").append(escape(Html.state(segment.alcoholicState()).toLowerCase(java.util.Locale.ROOT)))
            .append(" · FML ").append(escape(Html.state(segment.malolacticState()).toLowerCase(java.util.Locale.ROOT)))
            .append("</div></div>");

        if (block.series().isEmpty()) {
            h.append("<div class=\"empty\">Sin analíticas entre esas fechas.</div></div>");
            return;
        }

        // Charts, two per row.
        List<Event> events = d.events().stream().filter(e -> !e.at().isBefore(segment.from()) && !e.at().isAfter(segment.to())).toList();
        h.append("<table class=\"grid gap\" style=\"margin-top:8px\">");
        List<Series> series = block.series();
        for (int i = 0; i < series.size(); i += 2) {
            h.append("<tr>");
            for (int j = i; j < i + 2; j++) {
                h.append("<td style=\"width:50%\">");
                if (j < series.size()) chart(h, series.get(j), events, segment, segment.phase().color(), zone);
                h.append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");
        h.append("<div class=\"legend\">")
            .append("<span><span class=\"dot\" style=\"background-color:").append(SvgCharts.OK).append("\"></span>correcto</span>")
            .append("<span><span class=\"dot\" style=\"background-color:").append(SvgCharts.WARN).append("\"></span>aviso</span>")
            .append("<span><span class=\"dot\" style=\"background-color:").append(SvgCharts.CRIT).append("\"></span>crítico</span>")
            .append("<span><span class=\"dot hollow\"></span>provisional</span>")
            .append("<span><span class=\"tri\"></span>menor que el límite</span>")
            .append("<span><span class=\"band warnband\"></span>fuera de aviso</span>")
            .append("<span><span class=\"band critband\"></span>fuera de crítico</span>")
            .append("<span>líneas verticales: movimientos y revisiones</span>")
            .append("</div>");

        // Table of values, in chunks of columns.
        List<Parameter> columns = block.columns();
        for (int start = 0; start < columns.size(); start += TABLE_COLUMNS) {
            int end = Math.min(columns.size(), start + TABLE_COLUMNS);
            h.append("<table class=\"list\" style=\"margin-top:8px\"><thead><tr><th style=\"width:86px\">Toma</th><th style=\"width:92px\">Muestra</th>");
            for (int c = start; c < end; c++) {
                Parameter p = columns.get(c);
                h.append("<th class=\"right\">").append(escape(p.name()))
                    .append(p.unit() == null ? "" : "<div style=\"text-transform:none;font-weight:400\">" + escape(p.unit()) + "</div>")
                    .append("</th>");
            }
            h.append("</tr></thead><tbody>");
            for (TableRow row : block.table()) {
                boolean any = false;
                for (int c = start; c < end; c++) any |= row.cells().get(c) != null;
                if (!any) continue;
                h.append("<tr><td class=\"nowrap\">").append(escape(Html.dateTime(row.takenAt(), zone))).append("</td>")
                    .append("<td class=\"mono small nowrap").append(row.provisional() ? " prov" : "").append("\">").append(escape(row.sampleCode()))
                    .append(row.provisional() ? " *" : "").append("</td>");
                for (int c = start; c < end; c++) {
                    Cell cell = row.cells().get(c);
                    if (cell == null) { h.append("<td class=\"num soft\">·</td>"); continue; }
                    h.append("<td class=\"num st-").append(cell.status()).append(cell.validated() ? "" : " prov").append("\">")
                        .append(escape(Html.result(cell.value(), cell.qualifier(), cell.limit(), columns.get(c).decimals())))
                        .append("</td>");
                }
                h.append("</tr>");
            }
            h.append("</tbody></table>");
        }
        if (block.table().stream().anyMatch(TableRow::provisional)) {
            h.append("<div class=\"note\">* Muestra con resultados provisionales (sin validar).</div>");
        }
        h.append("</div>");
    }

    private void chart(StringBuilder h, Series series, List<Event> events, Segment segment, String color, ZoneId zone) {
        Parameter p = series.parameter();
        CellarReport.Point last = series.points().getLast();
        Range range = series.range();
        h.append("<div class=\"chart\"><table><tr><td><div class=\"ctitle\">").append(escape(p.name()))
            .append(p.unit() == null ? "" : " <span class=\"soft\" style=\"font-weight:400\">" + escape(p.unit()) + "</span>")
            .append("</div><div class=\"cmeta\">")
            .append(range == null ? "Sin objetivo definido" : escape(Html.range(range.warnMin(), range.warnMax(), range.critMin(), range.critMax(), p.decimals())))
            .append(" · ").append(series.points().size()).append(series.points().size() == 1 ? " valor" : " valores")
            .append("</div></td><td class=\"right\" style=\"width:90px\"><div class=\"mono st-").append(last.status())
            .append("\" style=\"font-size:11pt;font-weight:500\">")
            .append(escape(Html.result(last.value(), last.qualifier(), last.limit(), p.decimals()))).append("</div>")
            .append("<div class=\"cmeta\">").append(escape(Html.shortDate(last.at(), zone))).append("</div></td></tr></table>");
        h.append(SvgCharts.line(series.points(), range, events, segment.from(), segment.to(), color, zone, CHART_WIDTH));
        h.append("</div>");
    }

    // ------------------------------------------------------------------ appendix

    private void appendix(StringBuilder h, CellarReport report, ZoneId zone) {
        h.append("<div class=\"page\"><div class=\"section-title\" style=\"margin-top:0\">Cómo leer este informe</div>");
        h.append("<table class=\"list\"><tr><th style=\"width:150px\">Fase</th><th>Se asigna cuando</th><th>Parámetros graficados</th></tr>");
        for (PhaseRef phase : report.phases()) {
            h.append("<tr><td><span class=\"dot\" style=\"background-color:").append(phase.color()).append("\"></span><b>")
                .append(escape(phase.name())).append("</b>")
                .append(phase.description() == null ? "" : "<div class=\"tiny soft\">" + escape(phase.description()) + "</div>")
                .append("</td><td class=\"small\">").append(escape(phase.rule())).append("</td><td class=\"small muted\">")
                .append(escape(parameterNames(phase, report))).append("</td></tr>");
        }
        h.append("</table>");
        h.append("<div class=\"small muted\" style=\"margin-top:10px\">")
            .append("<p>La fase de cada depósito se calcula con su categoría y los estados de fermentación alcohólica (FA) y maloláctica (FML). ")
            .append("Las fases se evalúan en el orden de la tabla y se aplica la primera que encaja; se configuran en Administración › Analítica › Fases de informe.</p>")
            .append("<p>Las fases anteriores se reconstruyen con las revisiones de estado y los cambios de categoría fechados ")
            .append("(por ejemplo, un mosto que al terminar la fermentación alcohólica pasa a vino). Cada gráfica muestra solo los resultados ")
            .append("tomados mientras el contenido estaba en esa fase.</p>")
            .append("<p>El estado de cada resultado (correcto, aviso, crítico) se evalúa con el objetivo analítico que aplicaba en su fecha de toma, ")
            .append("según la categoría y la fase de fermentación de ese momento. «Sin objetivo» indica que no hay rango definido para ese parámetro.</p>")
            .append("<p>Solo se usan los resultados vigentes: una corrección sustituye al valor anterior y los análisis invalidados no aparecen. ")
            .append(report.meta().includeProvisional()
                ? "Este informe incluye resultados provisionales (sin validar), marcados como «prov.», con punto hueco o con asterisco."
                : "Este informe solo incluye resultados validados.")
            .append("</p><p>Un informe emitido no cambia: si se corrigen datos después, genera uno nuevo. El Excel que acompaña a este informe (")
            .append(escape(report.meta().code())).append(") contiene todos los datos en bruto.</p></div></div>");
    }

    // ------------------------------------------------------------------ small pieces

    private static String fact(String label, String value) {
        return fact(label, escape(value), true);
    }

    private static String fact(String label, String valueHtml, boolean html) {
        return "<td><div class=\"label\">" + escape(label) + "</div><div class=\"value\">" + valueHtml + "</div></td>";
    }

    private static String kpi(String label, String value, String tone) {
        return "<td><div class=\"kpi\"><div class=\"label\">" + escape(label) + "</div><div class=\"num " + tone + "\">"
            + escape(value) + "</div></div></td>";
    }

    private static String badge(String status) {
        String code = status == null ? "NONE" : status;
        return "<span class=\"badge badge-" + code + "\">" + escape(Html.status(code)) + "</span>";
    }

    private static String depositStatus(String status) {
        return switch (status == null ? "" : status) {
            case "AVAILABLE" -> "disponible";
            case "PENDING_CLEANING" -> "pendiente de limpieza";
            case "CLEANING" -> "en limpieza";
            case "MAINTENANCE" -> "mantenimiento";
            case "OCCUPIED" -> "ocupado";
            default -> status == null ? "" : status.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** The app icon (lucide wine glass on plum), as in public/icon-app.svg. */
    private static String logo(int size) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\" width=\"" + size + "\" height=\"" + size + "\">"
            + "<rect width=\"512\" height=\"512\" rx=\"112\" fill=\"#fdfbfc\"/>"
            + "<g transform=\"translate(124 124) scale(11)\" fill=\"none\" stroke=\"" + PLUM + "\" stroke-width=\"1.6\" "
            + "stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M8 22h8\"/><path d=\"M7 10h10\"/><path d=\"M12 15v7\"/>"
            + "<path d=\"M12 15a5 5 0 0 0 5-5c0-2-.5-4-2-8H9c-1.5 4-2 6-2 8a5 5 0 0 0 5 5Z\"/></g></svg>";
    }
}
