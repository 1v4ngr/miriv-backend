package coop.miriv.enology.report.render;

import coop.miriv.enology.report.model.CellarReport.Event;
import coop.miriv.enology.report.model.CellarReport.Point;
import coop.miriv.enology.report.model.CellarReport.Range;
import coop.miriv.enology.report.model.CellarReport.Segment;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hand-written SVG charts for the PDF report (rendered as vectors by the PDF engine). Colours follow the app:
 * plum lines, amber / red target bands, status-coloured points; provisional results are hollow points.
 */
public final class SvgCharts {

    static final String OK = "#2f7a4d";
    static final String WARN = "#c0902a";
    static final String CRIT = "#b3263f";
    static final String GRID = "#eee6ea";
    static final String AXIS = "#8a7f86";
    static final String INK = "#2e262a";

    private static final Locale ES = Locale.of("es", "ES");
    private static final double W = 520;
    private static final double H = 200;
    private static final double LEFT = 46;
    private static final double RIGHT = 12;
    private static final double TOP = 10;
    private static final double BOTTOM = 26;

    private SvgCharts() {}

    public static String statusColor(String status) {
        return switch (status == null ? "" : status) {
            case "OK" -> OK;
            case "WARN" -> WARN;
            case "CRIT" -> CRIT;
            default -> null;
        };
    }

    /** A line chart of one parameter over [from, to], with its target bands and the events of the period. */
    public static String line(List<Point> points, Range range, List<Event> events, Instant from, Instant to,
                              String color, ZoneId zone, double displayWidth) {
        double plotW = W - LEFT - RIGHT;
        double plotH = H - TOP - BOTTOM;
        long start = from.toEpochMilli();
        long end = Math.max(to.toEpochMilli(), start + 3_600_000L);
        for (Point point : points) {
            start = Math.min(start, point.at().toEpochMilli());
            end = Math.max(end, point.at().toEpochMilli());
        }
        long pad = Math.max((end - start) / 40, 1_800_000L);
        long x0 = start - pad;
        long x1 = end + pad;

        // Y domain: values, plus the limits that are close enough to matter.
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            double value = point.value().doubleValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (min == Double.POSITIVE_INFINITY) { min = 0; max = 1; }
        double spread = Math.max(max - min, Math.abs(max) * 0.05);
        if (spread == 0) spread = 1;
        if (range != null) {
            for (BigDecimal bound : new BigDecimal[] {range.warnMin(), range.warnMax(), range.critMin(), range.critMax()}) {
                if (bound == null) continue;
                double value = bound.doubleValue();
                if (value >= min - spread * 1.5 && value <= max + spread * 1.5) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }
        double[] ticks = niceTicks(min, max, 5);
        double y0 = ticks[0];
        double y1 = ticks[ticks.length - 1];
        if (y1 == y0) y1 = y0 + 1;

        final double fy0 = y0, fy1 = y1;
        final long fx0 = x0, fx1 = x1;
        java.util.function.DoubleUnaryOperator sy = value -> TOP + plotH - (value - fy0) / (fy1 - fy0) * plotH;
        java.util.function.LongToDoubleFunction sx = millis -> LEFT + (double) (millis - fx0) / (fx1 - fx0) * plotW;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(fmt(W)).append(' ').append(fmt(H))
            .append("\" width=\"").append(fmt(displayWidth)).append("\" height=\"").append(fmt(displayWidth * H / W)).append("\">");
        svg.append(rect(LEFT, TOP, plotW, plotH, "#ffffff", null));

        // Target bands: red beyond critical, amber between warning and critical.
        if (range != null) {
            if (range.critMin() != null) band(svg, sy, plotW, fy0, fy1, null, value(range.critMin()), "#f7e0e6");
            if (range.warnMin() != null) band(svg, sy, plotW, fy0, fy1, value(range.critMin()), value(range.warnMin()), "#fbefd8");
            if (range.warnMax() != null) band(svg, sy, plotW, fy0, fy1, value(range.warnMax()), value(range.critMax()), "#fbefd8");
            if (range.critMax() != null) band(svg, sy, plotW, fy0, fy1, value(range.critMax()), null, "#f7e0e6");
        }

        // Grid and Y labels.
        for (double tick : ticks) {
            double y = sy.applyAsDouble(tick);
            svg.append("<line x1=\"").append(fmt(LEFT)).append("\" y1=\"").append(fmt(y)).append("\" x2=\"").append(fmt(LEFT + plotW))
                .append("\" y2=\"").append(fmt(y)).append("\" stroke=\"").append(GRID).append("\" stroke-width=\"0.8\"/>");
            svg.append(text(LEFT - 6, y + 3.2, label(tick, ticks), "end", AXIS, 9));
        }

        // Limit lines.
        if (range != null) {
            limit(svg, sy, plotW, fy0, fy1, range.warnMin(), WARN);
            limit(svg, sy, plotW, fy0, fy1, range.warnMax(), WARN);
            limit(svg, sy, plotW, fy0, fy1, range.critMin(), CRIT);
            limit(svg, sy, plotW, fy0, fy1, range.critMax(), CRIT);
        }

        // X ticks.
        Duration span = Duration.ofMillis(x1 - x0);
        DateTimeFormatter format = DateTimeFormatter.ofPattern(span.toHours() <= 48 ? "dd/MM HH:mm" : "dd/MM", ES).withZone(zone);
        int count = 5;
        for (int i = 0; i <= count; i++) {
            long millis = x0 + (x1 - x0) * i / count;
            double x = sx.applyAsDouble(millis);
            svg.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(TOP + plotH)).append("\" x2=\"").append(fmt(x))
                .append("\" y2=\"").append(fmt(TOP + plotH + 3)).append("\" stroke=\"").append(AXIS).append("\" stroke-width=\"0.8\"/>");
            String anchor = i == 0 ? "start" : i == count ? "end" : "middle";
            svg.append(text(x, TOP + plotH + 14, format.format(Instant.ofEpochMilli(millis)), anchor, AXIS, 9));
        }

        // Events as thin vertical lines.
        for (Event event : events) {
            long millis = event.at().toEpochMilli();
            if (millis < x0 || millis > x1) continue;
            double x = sx.applyAsDouble(millis);
            String eventColor = eventColor(event.type());
            svg.append("<line x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(TOP)).append("\" x2=\"").append(fmt(x))
                .append("\" y2=\"").append(fmt(TOP + plotH)).append("\" stroke=\"").append(eventColor)
                .append("\" stroke-width=\"0.9\" stroke-dasharray=\"3,3\" stroke-opacity=\"0.8\"/>");
            svg.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(TOP + 3)).append("\" r=\"2.6\" fill=\"")
                .append(eventColor).append("\"/>");
        }

        // Axis frame.
        svg.append("<line x1=\"").append(fmt(LEFT)).append("\" y1=\"").append(fmt(TOP + plotH)).append("\" x2=\"")
            .append(fmt(LEFT + plotW)).append("\" y2=\"").append(fmt(TOP + plotH)).append("\" stroke=\"").append(AXIS)
            .append("\" stroke-width=\"0.9\"/>");

        // The line through the measured values, then the points.
        if (points.size() > 1) {
            StringBuilder path = new StringBuilder();
            for (Point point : points) {
                path.append(path.isEmpty() ? "M" : " L").append(fmt(sx.applyAsDouble(point.at().toEpochMilli()))).append(',')
                    .append(fmt(sy.applyAsDouble(point.value().doubleValue())));
            }
            svg.append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"1.7\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
        }
        for (Point point : points) {
            double x = sx.applyAsDouble(point.at().toEpochMilli());
            double y = sy.applyAsDouble(point.value().doubleValue());
            String status = statusColor(point.status());
            String stroke = status == null ? color : status;
            String fill = point.validated() ? stroke : "#ffffff";
            if ("LESS_THAN".equals(point.qualifier()) || "NOT_DETECTED".equals(point.qualifier())) {
                svg.append("<path d=\"M").append(fmt(x - 3.6)).append(',').append(fmt(y - 2.6)).append(" L").append(fmt(x + 3.6))
                    .append(',').append(fmt(y - 2.6)).append(" L").append(fmt(x)).append(',').append(fmt(y + 3.4))
                    .append(" Z\" fill=\"").append(fill).append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1.3\"/>");
            } else {
                svg.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y)).append("\" r=\"3\" fill=\"")
                    .append(fill).append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1.3\"/>");
            }
        }
        svg.append("</svg>");
        return svg.toString();
    }

    /** A horizontal strip with the phases a content went through over [from, to]. */
    public static String timeline(List<Segment> segments, Instant from, Instant to, List<Event> events, double displayWidth) {
        double width = 1000;
        double height = 22;
        long start = from.toEpochMilli();
        long end = Math.max(to.toEpochMilli(), start + 1);
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(fmt(width)).append(' ').append(fmt(height))
            .append("\" width=\"").append(fmt(displayWidth)).append("\" height=\"").append(fmt(displayWidth * height / width)).append("\">");
        svg.append("<rect x=\"0\" y=\"4\" width=\"").append(fmt(width)).append("\" height=\"14\" rx=\"7\" fill=\"#f3e7ee\"/>");
        List<double[]> spans = new ArrayList<>();
        for (Segment segment : segments) {
            double a = (double) (segment.from().toEpochMilli() - start) / (end - start) * width;
            double b = (double) (segment.to().toEpochMilli() - start) / (end - start) * width;
            spans.add(new double[] {a, Math.max(b, a + 2)});
            svg.append("<rect x=\"").append(fmt(a)).append("\" y=\"4\" width=\"").append(fmt(Math.max(b - a, 2)))
                .append("\" height=\"14\" fill=\"").append(segment.phase().color()).append("\"/>");
        }
        for (double[] span : spans) {
            if (span[0] > 0.5) {
                svg.append("<line x1=\"").append(fmt(span[0])).append("\" y1=\"2\" x2=\"").append(fmt(span[0]))
                    .append("\" y2=\"20\" stroke=\"#ffffff\" stroke-width=\"2\"/>");
            }
        }
        for (Event event : events) {
            double x = (double) (event.at().toEpochMilli() - start) / (end - start) * width;
            if (x < 0 || x > width) continue;
            svg.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"11\" r=\"3.2\" fill=\"#ffffff\" stroke=\"")
                .append(eventColor(event.type())).append("\" stroke-width=\"1.6\"/>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    /** Small filled square used as a colour key in the HTML legends. */
    public static String swatch(String color, boolean hollow, boolean triangle) {
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\" width=\"10\" height=\"10\">");
        if (triangle) {
            svg.append("<path d=\"M1,2 L9,2 L5,9 Z\" fill=\"").append(hollow ? "#ffffff" : color).append("\" stroke=\"").append(color)
                .append("\" stroke-width=\"1.3\"/>");
        } else {
            svg.append("<circle cx=\"5\" cy=\"5\" r=\"3.6\" fill=\"").append(hollow ? "#ffffff" : color).append("\" stroke=\"")
                .append(color).append("\" stroke-width=\"1.3\"/>");
        }
        return svg.append("</svg>").toString();
    }

    public static String eventColor(String type) {
        return switch (type == null ? "" : type) {
            case "TRANSFER" -> "#2e7d9a";
            case "ENTRY" -> "#4d8b4f";
            case "MIX", "SPLIT" -> "#8e4fa8";
            case "EXIT" -> "#4a5568";
            case "LOSS" -> "#b8423f";
            case "ADJUSTMENT" -> "#8a8f2a";
            case "STATE_REVIEW" -> "#6d4656";
            default -> "#c0782a";
        };
    }

    // ------------------------------------------------------------------ helpers

    private static void band(StringBuilder svg, java.util.function.DoubleUnaryOperator sy, double plotW, double y0, double y1,
                             Double low, Double high, String color) {
        double a = low == null ? y0 : Math.max(low, y0);
        double b = high == null ? y1 : Math.min(high, y1);
        if (b <= a) return;
        double top = sy.applyAsDouble(b);
        double bottom = sy.applyAsDouble(a);
        svg.append(rect(LEFT, top, plotW, bottom - top, color, null));
    }

    private static void limit(StringBuilder svg, java.util.function.DoubleUnaryOperator sy, double plotW, double y0, double y1,
                              BigDecimal bound, String color) {
        if (bound == null) return;
        double value = bound.doubleValue();
        if (value < y0 || value > y1) return;
        double y = sy.applyAsDouble(value);
        svg.append("<line x1=\"").append(fmt(LEFT)).append("\" y1=\"").append(fmt(y)).append("\" x2=\"").append(fmt(LEFT + plotW))
            .append("\" y2=\"").append(fmt(y)).append("\" stroke=\"").append(color).append("\" stroke-width=\"0.9\" stroke-dasharray=\"5,3\"/>");
    }

    private static Double value(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String rect(double x, double y, double w, double h, String fill, String stroke) {
        return "<rect x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" width=\"" + fmt(w) + "\" height=\"" + fmt(h) + "\" fill=\"" + fill
            + "\"" + (stroke == null ? "" : " stroke=\"" + stroke + "\"") + "/>";
    }

    private static String text(double x, double y, String value, String anchor, String color, double size) {
        return "<text x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" text-anchor=\"" + anchor + "\" font-family=\"Jakarta\" font-size=\""
            + fmt(size) + "\" fill=\"" + color + "\">" + Html.escape(value) + "</text>";
    }

    /** Round tick values: 1, 2, 2.5 or 5 times a power of ten. */
    static double[] niceTicks(double min, double max, int target) {
        if (max <= min) { max = min + 1; }
        double raw = (max - min) / Math.max(target - 1, 1);
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double step = magnitude;
        for (double factor : new double[] {1, 2, 2.5, 5, 10}) {
            step = factor * magnitude;
            if (raw <= step) break;
        }
        double low = Math.floor(min / step) * step;
        double high = Math.ceil(max / step) * step;
        int n = (int) Math.round((high - low) / step);
        double[] ticks = new double[n + 1];
        for (int i = 0; i <= n; i++) ticks[i] = low + i * step;
        return ticks;
    }

    private static String label(double tick, double[] ticks) {
        double step = ticks.length > 1 ? Math.abs(ticks[1] - ticks[0]) : 1;
        int decimals = step >= 1 ? 0 : (int) Math.min(4, Math.ceil(-Math.log10(step) - 1e-9));
        String text = String.format(ES, "%." + decimals + "f", tick);
        return "-0".equals(text) ? "0" : text;
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
