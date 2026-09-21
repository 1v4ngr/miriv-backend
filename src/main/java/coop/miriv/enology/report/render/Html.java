package coop.miriv.enology.report.render;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Escaping, Spanish number / date formatting and the labels shared by the PDF and the .xlsx. */
public final class Html {

    private static final Locale ES = Locale.of("es", "ES");

    public static final Map<String, String> STATE_LABELS = Map.of(
        "NOT_STARTED", "No iniciada",
        "ACTIVE", "Activa",
        "SLOW", "Lenta",
        "SUSPECTED_STOP", "Sospecha de parada",
        "FINISHED", "Finalizada",
        "NOT_EXPECTED", "No prevista",
        "NONE", "Sin estado");

    public static final Map<String, String> STATUS_LABELS = Map.of(
        "OK", "Correcto",
        "WARN", "Aviso",
        "CRIT", "Crítico",
        "UNKNOWN", "Indeterminado",
        "NONE", "Sin objetivo");

    public static final Map<String, String> EVENT_LABELS = Map.of(
        "TRANSFER", "Trasiego",
        "ENTRY", "Entrada",
        "MIX", "Mezcla",
        "SPLIT", "Desdoble",
        "EXIT", "Salida",
        "LOSS", "Merma",
        "ADJUSTMENT", "Ajuste",
        "STATE_REVIEW", "Revisión de estado");

    private Html() {}

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    // XML 1.0 forbids most control characters.
                    if (c >= 0x20 || c == '\n' || c == '\t') out.append(c);
                }
            }
        }
        return out.toString();
    }

    public static String state(String code) {
        if (code == null || code.isBlank()) return "Sin estado";
        return STATE_LABELS.getOrDefault(code, code);
    }

    public static String status(String code) {
        return STATUS_LABELS.getOrDefault(code == null ? "NONE" : code, code);
    }

    public static String event(String type) {
        return EVENT_LABELS.getOrDefault(type, type == null ? "" : type);
    }

    /** "1.234,56" with the given decimals (trailing zeros kept, as the lab reports them). */
    public static String number(BigDecimal value, int decimals) {
        if (value == null) return "—";
        DecimalFormat format = new DecimalFormat(decimals > 0 ? "#,##0." + "0".repeat(decimals) : "#,##0",
            DecimalFormatSymbols.getInstance(ES));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    public static String liters(BigDecimal value) {
        return value == null ? "—" : number(value, 0) + "\u00A0L";
    }

    /** The value as the lab reported it: "0,72", "< 0,05", "n.d.", "n.m.". */
    public static String result(BigDecimal value, String qualifier, BigDecimal limit, int decimals) {
        String q = qualifier == null ? "NONE" : qualifier;
        return switch (q) {
            case "LESS_THAN" -> "< " + number(limit != null ? limit : value, decimals);
            case "NOT_DETECTED" -> "n.d.";
            case "NOT_MEASURED" -> "n.m.";
            default -> number(value, decimals);
        };
    }

    public static String range(BigDecimal warnMin, BigDecimal warnMax, BigDecimal critMin, BigDecimal critMax, int decimals) {
        StringBuilder out = new StringBuilder();
        if (warnMin != null || warnMax != null) {
            out.append("Aviso ").append(bounds(warnMin, warnMax, decimals));
        }
        if (critMin != null || critMax != null) {
            if (!out.isEmpty()) out.append(" · ");
            out.append("Crítico ").append(bounds(critMin, critMax, decimals));
        }
        return out.toString();
    }

    private static String bounds(BigDecimal min, BigDecimal max, int decimals) {
        if (min != null && max != null) return number(min, decimals) + "–" + number(max, decimals);
        if (min != null) return "< " + number(min, decimals);
        return "> " + number(max, decimals);
    }

    public static String date(Instant value, ZoneId zone) {
        return value == null ? "—" : DateTimeFormatter.ofPattern("dd/MM/yyyy", ES).withZone(zone).format(value);
    }

    public static String shortYearDate(Instant value, ZoneId zone) {
        return value == null ? "—" : DateTimeFormatter.ofPattern("dd/MM/yy", ES).withZone(zone).format(value);
    }

    public static String shortDate(Instant value, ZoneId zone) {
        return value == null ? "—" : DateTimeFormatter.ofPattern("dd/MM", ES).withZone(zone).format(value);
    }

    public static String dateTime(Instant value, ZoneId zone) {
        return value == null ? "—" : DateTimeFormatter.ofPattern("dd/MM/yyyy'\u00A0'HH:mm", ES).withZone(zone).format(value);
    }

    public static String longDateTime(Instant value, ZoneId zone) {
        return value == null ? "—" : DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ES).withZone(zone).format(value);
    }

    public static String days(long days) {
        return days == 1 ? "1\u00A0día" : days + "\u00A0días";
    }
}
