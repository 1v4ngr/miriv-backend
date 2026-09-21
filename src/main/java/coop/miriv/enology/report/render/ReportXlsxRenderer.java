package coop.miriv.enology.report.render;

import coop.miriv.enology.report.model.CellarReport;
import coop.miriv.enology.report.model.CellarReport.Alert;
import coop.miriv.enology.report.model.CellarReport.AnalyticRow;
import coop.miriv.enology.report.model.CellarReport.DepositReport;
import coop.miriv.enology.report.model.CellarReport.EmptyDeposit;
import coop.miriv.enology.report.model.CellarReport.Event;
import coop.miriv.enology.report.model.CellarReport.Latest;
import coop.miriv.enology.report.model.CellarReport.Parameter;
import coop.miriv.enology.report.model.CellarReport.PhaseCount;
import coop.miriv.enology.report.model.CellarReport.PhaseRef;
import coop.miriv.enology.report.model.CellarReport.Range;
import coop.miriv.enology.report.model.CellarReport.Segment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link CellarReport} as a raw-data workbook: summary, deposits, every analytical result in long
 * format (one row per result, ready for pivot tables), phase segments, events, alerts, the phase settings used,
 * and one wide sheet per deposit (one row per sample, one column per parameter).
 */
@Component
public class ReportXlsxRenderer {

    private static final int MAX_DEPOSIT_SHEETS = 80;

    public byte[] render(CellarReport report) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(book);
            ZoneId zone = ZoneId.of(report.meta().timezone());
            summary(book, styles, report, zone);
            deposits(book, styles, report, zone);
            analytics(book, styles, report, zone);
            phases(book, styles, report, zone);
            events(book, styles, report, zone);
            alerts(book, styles, report, zone);
            settings(book, styles, report);
            perDeposit(book, styles, report, zone);
            book.getProperties().getCoreProperties().setTitle(report.meta().title() + " · " + report.meta().code());
            book.getProperties().getCoreProperties().setCreator(report.meta().author());
            book.getProperties().getCoreProperties().setDescription(report.meta().scope());
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ sheets

    private void summary(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Resumen");
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 70 * 256);
        CellarReport.Meta meta = report.meta();
        int r = 0;
        Row title = sheet.createRow(r++);
        title.setHeightInPoints(28);
        text(title, 0, meta.title(), s.title);
        text(sheet.createRow(r++), 0, meta.centerName(), s.subtitle);
        r++;
        r = pair(sheet, r, "Código del informe", meta.code(), s);
        r = pair(sheet, r, "Emitido", Html.dateTime(meta.generatedAt(), zone), s);
        r = pair(sheet, r, "Autor", meta.author(), s);
        r = pair(sheet, r, "Alcance", meta.scope(), s);
        r = pair(sheet, r, "Resultados", meta.includeProvisional() ? "Validados y provisionales" : "Solo validados", s);
        r = pair(sheet, r, "Zona horaria de fechas", meta.timezone(), s);
        r = pair(sheet, r, "Depósitos con contenido", String.valueOf(report.deposits().size()), s);
        r = pair(sheet, r, "Registros analíticos", String.valueOf(report.rows().size()), s);
        r++;
        Row head = sheet.createRow(r++);
        String[] headers = {"Fase", "Depósitos", "Volumen (L)", "En aviso", "En crítico", "Parámetros"};
        for (int c = 0; c < headers.length; c++) text(head, c, headers[c], s.header);
        for (PhaseCount count : report.phaseCounts()) {
            Row row = sheet.createRow(r++);
            text(row, 0, count.phase().name(), s.text);
            number(row, 1, count.deposits(), s.integer);
            number(row, 2, count.volumeLiters(), s.integer);
            number(row, 3, count.warn(), s.integer);
            number(row, 4, count.crit(), s.integer);
            text(row, 5, report.parameterNames(count.phase().parameters()), s.text);
        }
        for (int c = 2; c <= 5; c++) sheet.setColumnWidth(c, (c == 5 ? 60 : 13) * 256);
        r++;
        Row note = sheet.createRow(r);
        text(note, 0, "Un informe emitido no cambia. Los estados (Correcto / Aviso / Crítico) usan el objetivo que aplicaba en la fecha de toma.", s.note);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 5));
    }

    private void deposits(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Depósitos");
        String[] headers = {"Depósito", "Nombre", "Zona", "Contenido", "Lote", "Categoría", "Fase", "En fase desde",
            "Estado FA", "Estado FML", "Volumen (L)", "Capacidad útil (L)", "Llenado (%)", "Plan", "Entrada en depósito",
            "Inicio contenido", "Periodo desde", "Periodo hasta", "Muestras", "Última muestra", "Estado analítico",
            "Avisos activos"};
        header(sheet, headers, s);
        int r = 1;
        for (DepositReport d : report.deposits()) {
            Row row = sheet.createRow(r++);
            int c = 0;
            text(row, c++, d.deposit(), s.bold);
            text(row, c++, d.depositName(), s.text);
            text(row, c++, d.zone(), s.text);
            text(row, c++, d.content(), s.mono);
            text(row, c++, d.lot(), s.mono);
            text(row, c++, d.categoryName(), s.text);
            text(row, c++, d.phase().name(), s.text);
            date(row, c++, d.phaseSince(), zone, s);
            text(row, c++, Html.state(d.alcoholicState()), s.text);
            text(row, c++, Html.state(d.malolacticState()), s.text);
            number(row, c++, d.volumeLiters(), s.integer);
            number(row, c++, d.capacityLiters(), s.integer);
            number(row, c++, d.fillPercent(), s.integer);
            text(row, c++, d.plan(), s.text);
            date(row, c++, d.enteredDeposit(), zone, s);
            date(row, c++, d.contentStart(), zone, s);
            date(row, c++, d.from(), zone, s);
            date(row, c++, d.to(), zone, s);
            number(row, c++, d.sampleCount(), s.integer);
            date(row, c++, d.lastSampleAt(), zone, s);
            text(row, c++, Html.status(d.worstStatus()), s.status(d.worstStatus()));
            number(row, c, d.alerts().size(), s.integer);
        }
        for (EmptyDeposit empty : report.emptyDeposits()) {
            Row row = sheet.createRow(r++);
            text(row, 0, empty.deposit(), s.bold);
            text(row, 1, empty.depositName(), s.text);
            text(row, 2, empty.zone(), s.text);
            text(row, 6, "Sin contenido", s.muted);
            number(row, 11, empty.capacityLiters(), s.integer);
        }
        finish(sheet, headers.length, r, new int[] {11, 18, 12, 14, 14, 12, 24, 16, 16, 16, 12, 14, 11, 22, 16, 16, 16, 16, 10, 16, 15, 12});
    }

    private void analytics(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Analíticas");
        String[] headers = {"Depósito", "Contenido", "Lote", "Depósito de toma", "Muestra", "Fecha de toma",
            "Categoría en la toma", "Fase en la toma", "FA en la toma", "FML en la toma", "Código parámetro", "Parámetro",
            "Valor", "Calificador", "Límite", "Valor informado", "Unidad", "Método", "Laboratorio", "Validado",
            "Validado por", "Fecha de validación", "Estado", "Aviso mín.", "Aviso máx.", "Crítico mín.", "Crítico máx."};
        header(sheet, headers, s);
        int r = 1;
        for (AnalyticRow a : report.rows()) {
            Row row = sheet.createRow(r++);
            int c = 0;
            int decimals = a.parameter().decimals();
            text(row, c++, a.deposit(), s.text);
            text(row, c++, a.content(), s.mono);
            text(row, c++, a.lot(), s.mono);
            text(row, c++, a.depositAtSampling(), s.text);
            text(row, c++, a.sampleCode(), s.mono);
            date(row, c++, a.takenAt(), zone, s);
            text(row, c++, a.categoryName(), s.text);
            text(row, c++, a.phase().name(), s.text);
            text(row, c++, Html.state(a.alcoholicState()), s.text);
            text(row, c++, Html.state(a.malolacticState()), s.text);
            text(row, c++, a.parameter().code(), s.mono);
            text(row, c++, a.parameter().name(), s.text);
            number(row, c++, a.value(), s.decimal(decimals));
            text(row, c++, qualifier(a.qualifier()), s.text);
            number(row, c++, a.limit(), s.decimal(decimals));
            text(row, c++, Html.result(a.value(), a.qualifier(), a.limit(), decimals), s.right);
            text(row, c++, a.parameter().unit(), s.text);
            text(row, c++, a.method(), s.text);
            text(row, c++, a.laboratory(), s.text);
            text(row, c++, a.validated() ? "Sí" : "No", a.validated() ? s.text : s.provisional);
            text(row, c++, a.validatedBy(), s.text);
            date(row, c++, a.validatedAt(), zone, s);
            text(row, c++, Html.status(a.status()), s.status(a.status()));
            Range range = a.range();
            number(row, c++, range == null ? null : range.warnMin(), s.decimal(decimals));
            number(row, c++, range == null ? null : range.warnMax(), s.decimal(decimals));
            number(row, c++, range == null ? null : range.critMin(), s.decimal(decimals));
            number(row, c, range == null ? null : range.critMax(), s.decimal(decimals));
        }
        finish(sheet, headers.length, r, new int[] {11, 16, 16, 12, 18, 16, 14, 22, 14, 14, 20, 24, 11, 12, 10, 12, 16, 18,
            18, 9, 18, 16, 13, 10, 10, 10, 10});
    }

    private void phases(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Fases");
        String[] headers = {"Depósito", "Contenido", "Fase", "Desde", "Hasta", "Días", "Categoría", "Estado FA", "Estado FML"};
        header(sheet, headers, s);
        int r = 1;
        for (DepositReport d : report.deposits()) {
            for (Segment segment : d.segments()) {
                Row row = sheet.createRow(r++);
                text(row, 0, d.deposit(), s.text);
                text(row, 1, d.content(), s.mono);
                text(row, 2, segment.phase().name(), s.text);
                date(row, 3, segment.from(), zone, s);
                date(row, 4, segment.to(), zone, s);
                number(row, 5, BigDecimal.valueOf(Duration.between(segment.from(), segment.to()).toHours() / 24.0)
                    .setScale(1, java.math.RoundingMode.HALF_UP), s.decimal(1));
                text(row, 6, segment.categoryName(), s.text);
                text(row, 7, Html.state(segment.alcoholicState()), s.text);
                text(row, 8, Html.state(segment.malolacticState()), s.text);
            }
        }
        finish(sheet, headers.length, r, new int[] {11, 16, 24, 16, 16, 8, 14, 16, 16});
    }

    private void events(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Eventos");
        String[] headers = {"Depósito", "Contenido", "Fecha", "Tipo", "Descripción", "Detalle"};
        header(sheet, headers, s);
        int r = 1;
        for (DepositReport d : report.deposits()) {
            for (Event event : d.events()) {
                Row row = sheet.createRow(r++);
                text(row, 0, d.deposit(), s.text);
                text(row, 1, d.content(), s.mono);
                date(row, 2, event.at(), zone, s);
                text(row, 3, Html.event(event.type()), s.text);
                text(row, 4, event.label(), s.text);
                text(row, 5, event.detail(), s.text);
            }
        }
        finish(sheet, headers.length, r, new int[] {11, 16, 16, 18, 36, 60});
    }

    private void alerts(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Sheet sheet = book.createSheet("Avisos");
        String[] headers = {"Depósito", "Contenido", "Aviso", "Gravedad", "Desde", "Detalle"};
        header(sheet, headers, s);
        int r = 1;
        for (DepositReport d : report.deposits()) {
            for (Alert alert : d.alerts()) {
                Row row = sheet.createRow(r++);
                text(row, 0, d.deposit(), s.text);
                text(row, 1, d.content(), s.mono);
                text(row, 2, alert.rule(), s.text);
                String severity = switch (alert.severity()) { case "CRIT" -> "Crítico"; case "WARN" -> "Aviso"; default -> "Informativo"; };
                text(row, 3, severity, s.status(alert.severity()));
                date(row, 4, alert.since(), zone, s);
                text(row, 5, alert.detail(), s.text);
            }
        }
        finish(sheet, headers.length, r, new int[] {11, 16, 30, 12, 16, 60});
    }

    private void settings(XSSFWorkbook book, Styles s, CellarReport report) {
        Sheet sheet = book.createSheet("Config. fases");
        String[] headers = {"Orden", "Código", "Fase", "Se asigna cuando", "Parámetros graficados", "Descripción"};
        header(sheet, headers, s);
        int r = 1;
        for (PhaseRef phase : report.phases()) {
            Row row = sheet.createRow(r);
            number(row, 0, r, s.integer);
            text(row, 1, phase.code(), s.mono);
            text(row, 2, phase.name(), s.bold);
            text(row, 3, phase.rule(), s.text);
            text(row, 4, report.parameterNames(phase.parameters()), s.text);
            text(row, 5, phase.description(), s.text);
            r++;
        }
        finish(sheet, headers.length, r, new int[] {7, 16, 24, 60, 60, 50});
    }

    /** One sheet per deposit: a row per sample, a column per parameter (all parameters with data). */
    private void perDeposit(XSSFWorkbook book, Styles s, CellarReport report, ZoneId zone) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < book.getNumberOfSheets(); i++) used.add(book.getSheetName(i).toLowerCase(Locale.ROOT));
        int count = 0;
        for (DepositReport d : report.deposits()) {
            if (count++ >= MAX_DEPOSIT_SHEETS) break;
            List<AnalyticRow> rows = report.rows().stream().filter(row -> row.content().equals(d.content())).toList();
            String name = uniqueName("Dep " + d.deposit(), used);
            Sheet sheet = book.createSheet(name);

            Row title = sheet.createRow(0);
            text(title, 0, "Depósito " + d.deposit() + " · " + d.content() + " · " + (d.categoryName() == null ? "" : d.categoryName())
                + " · fase actual: " + d.phase().name(), s.subtitle);

            Map<String, Parameter> parameters = new LinkedHashMap<>();
            // Current phase parameters first, then the rest alphabetically.
            for (String code : d.phase().parameters()) {
                rows.stream().filter(row -> row.parameter().code().equals(code)).findFirst()
                    .ifPresent(row -> parameters.put(code, row.parameter()));
            }
            rows.stream().map(AnalyticRow::parameter).sorted(java.util.Comparator.comparing(Parameter::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(parameter -> parameters.putIfAbsent(parameter.code(), parameter));

            List<String> headers = new ArrayList<>(List.of("Fecha de toma", "Muestra", "Fase", "Validada"));
            parameters.values().forEach(parameter -> headers.add(parameter.name() + (parameter.unit() == null ? "" : " (" + parameter.unit() + ")")));
            Row head = sheet.createRow(2);
            for (int c = 0; c < headers.size(); c++) text(head, c, headers.get(c), s.header);

            Map<String, List<AnalyticRow>> bySample = new LinkedHashMap<>();
            rows.forEach(row -> bySample.computeIfAbsent(row.sampleCode(), k -> new ArrayList<>()).add(row));
            int r = 3;
            List<String> codes = new ArrayList<>(parameters.keySet());
            for (List<AnalyticRow> sample : bySample.values()) {
                Row row = sheet.createRow(r++);
                AnalyticRow first = sample.getFirst();
                date(row, 0, first.takenAt(), zone, s);
                text(row, 1, first.sampleCode(), s.mono);
                text(row, 2, first.phase().name(), s.text);
                boolean validated = sample.stream().allMatch(AnalyticRow::validated);
                text(row, 3, validated ? "Sí" : "No", validated ? s.text : s.provisional);
                Map<String, AnalyticRow> values = new HashMap<>();
                sample.forEach(item -> values.put(item.parameter().code(), item));
                for (int c = 0; c < codes.size(); c++) {
                    AnalyticRow value = values.get(codes.get(c));
                    if (value == null) continue;
                    int decimals = value.parameter().decimals();
                    if (value.value() != null && ("NONE".equals(value.qualifier()) || value.qualifier() == null)) {
                        number(row, 4 + c, value.value(), s.decimal(decimals, value.status()));
                    } else {
                        text(row, 4 + c, Html.result(value.value(), value.qualifier(), value.limit(), decimals), s.right);
                    }
                }
            }
            if (rows.isEmpty()) text(sheet.createRow(3), 0, "Sin resultados en el periodo del informe.", s.muted);
            sheet.createFreezePane(2, 3);
            if (r > 3) sheet.setAutoFilter(new CellRangeAddress(2, r - 1, 0, headers.size() - 1));
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 22 * 256);
            sheet.setColumnWidth(3, 9 * 256);
            for (int c = 4; c < headers.size(); c++) sheet.setColumnWidth(c, Math.min(Math.max(headers.get(c).length() + 3, 11), 26) * 256);
            head.setHeightInPoints(30);

            // Latest values with the phase of today, under the table.
            int lr = r + 1;
            if (!d.latest().isEmpty()) {
                text(sheet.createRow(lr++), 0, "Últimos valores del periodo", s.bold);
                for (Latest latest : d.latest()) {
                    Row row = sheet.createRow(lr++);
                    text(row, 0, latest.parameter().name(), s.text);
                    text(row, 1, Html.result(latest.row().value(), latest.row().qualifier(), latest.row().limit(), latest.parameter().decimals())
                        + (latest.parameter().unit() == null ? "" : " " + latest.parameter().unit()), s.right);
                    text(row, 2, Html.status(latest.row().status()), s.status(latest.row().status()));
                    date(row, 3, latest.row().takenAt(), zone, s);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String uniqueName(String base, Set<String> used) {
        String safe = WorkbookUtil.createSafeSheetName(base).replace('\'', ' ');
        if (safe.length() > 28) safe = safe.substring(0, 28);
        String name = safe;
        int n = 2;
        while (used.contains(name.toLowerCase(Locale.ROOT))) name = safe + " " + n++;
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static String qualifier(String qualifier) {
        return switch (qualifier == null ? "NONE" : qualifier) {
            case "LESS_THAN" -> "Menor que";
            case "NOT_DETECTED" -> "No detectado";
            case "NOT_MEASURED" -> "No medido";
            default -> "";
        };
    }

    private static int pair(Sheet sheet, int r, String label, String value, Styles s) {
        Row row = sheet.createRow(r);
        text(row, 0, label, s.label);
        text(row, 1, value, s.wrap);
        return r + 1;
    }

    private static void header(Sheet sheet, String[] headers, Styles s) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(30);
        for (int c = 0; c < headers.length; c++) text(row, c, headers[c], s.header);
    }

    private static void finish(Sheet sheet, int columns, int rows, int[] widths) {
        sheet.createFreezePane(0, 1);
        if (rows > 1) sheet.setAutoFilter(new CellRangeAddress(0, rows - 1, 0, columns - 1));
        for (int c = 0; c < Math.min(columns, widths.length); c++) sheet.setColumnWidth(c, widths[c] * 256);
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, Number value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(value instanceof BigDecimal big ? big.doubleValue() : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void date(Row row, int column, Instant value, ZoneId zone, Styles s) {
        Cell cell = row.createCell(column);
        if (value != null) cell.setCellValue(LocalDateTime.ofInstant(value, zone));
        cell.setCellStyle(s.date);
    }

    /** Cell styles, created once per workbook (Excel caps the number of styles). */
    private static final class Styles {
        private final XSSFWorkbook book;
        final CellStyle title, subtitle, header, text, bold, mono, muted, note, label, wrap, right, integer, date, provisional;
        private final Map<String, CellStyle> decimals = new HashMap<>();
        private final Map<String, CellStyle> statuses = new HashMap<>();

        Styles(XSSFWorkbook book) {
            this.book = book;
            title = style(font(18, true, "452833"), null, null);
            subtitle = style(font(11, true, "6d4656"), null, null);
            header = style(font(10, true, "FFFFFF"), "6d4656", null);
            header.setWrapText(true);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            text = style(font(10, false, "2e262a"), null, null);
            bold = style(font(10, true, "2e262a"), null, null);
            mono = style(font(10, false, "2e262a"), null, null);
            ((XSSFCellStyle) mono).getFont().setFontName("Consolas");
            muted = style(font(10, false, "8a7f86"), null, null);
            note = style(font(9, false, "6f6169"), null, null);
            note.setWrapText(true);
            label = style(font(10, true, "6f6169"), null, null);
            wrap = style(font(10, false, "2e262a"), null, null);
            wrap.setWrapText(true);
            right = style(font(10, false, "2e262a"), null, null);
            right.setAlignment(HorizontalAlignment.RIGHT);
            integer = style(font(10, false, "2e262a"), null, "#,##0");
            date = style(font(10, false, "2e262a"), null, "dd/mm/yyyy hh:mm");
            provisional = style(font(10, true, "9a6d12"), null, null);
        }

        CellStyle decimal(int places) {
            return decimal(places, null);
        }

        /** Number with the parameter's decimals; WARN / CRIT get a soft fill so they stand out when scanning. */
        CellStyle decimal(int places, String status) {
            String fill = switch (status == null ? "" : status) { case "WARN" -> "f8ebcc"; case "CRIT" -> "f7e0e6"; default -> null; };
            String key = places + "|" + fill;
            return decimals.computeIfAbsent(key, k -> style(font(10, false, "2e262a"), fill,
                places > 0 ? "#,##0." + "0".repeat(Math.min(places, 6)) : "#,##0"));
        }

        CellStyle status(String status) {
            return statuses.computeIfAbsent(status == null ? "NONE" : status, key -> switch (key) {
                case "OK" -> style(font(10, true, "1f5c3a"), "dceadf", null);
                case "WARN" -> style(font(10, true, "7a5410"), "f8ebcc", null);
                case "CRIT" -> style(font(10, true, "8e1f33"), "f7e0e6", null);
                default -> style(font(10, false, "6f6169"), null, null);
            });
        }

        private XSSFFont font(int size, boolean bold, String rgb) {
            XSSFFont font = book.createFont();
            font.setFontName("Calibri");
            font.setFontHeightInPoints((short) size);
            font.setBold(bold);
            font.setColor(color(rgb));
            return font;
        }

        private CellStyle style(XSSFFont font, String fill, String format) {
            XSSFCellStyle style = book.createCellStyle();
            style.setFont(font);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            if (fill != null) {
                style.setFillForegroundColor(color(fill));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (format != null) style.setDataFormat(book.createDataFormat().getFormat(format));
            style.setBorderBottom(BorderStyle.HAIR);
            style.setBottomBorderColor(color("eadfe4"));
            return style;
        }

        private static XSSFColor color(String rgb) {
            String hex = rgb.startsWith("#") ? rgb.substring(1) : rgb;
            return new XSSFColor(new byte[] {(byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16), (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
        }
    }
}
