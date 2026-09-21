package coop.miriv.enology.cellar.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fermentation states travel and are stored as stable codes (ACTIVE, FINISHED…), the same codes alert
 * rules and analytical targets are scoped by. Confirmations used to be stored as the Spanish label
 * ("Activa"), which never matched those codes: a rule limited to ACTIVE stopped firing as soon as an
 * enologist confirmed the state. Spanish labels are still accepted on input for older clients.
 */
public final class FermentationPhase {

    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String ACTIVE = "ACTIVE";
    public static final String SLOW = "SLOW";
    public static final String SUSPECTED_STOP = "SUSPECTED_STOP";
    public static final String FINISHED = "FINISHED";
    public static final String NOT_EXPECTED = "NOT_EXPECTED";

    public static final Set<String> ALCOHOLIC = Set.of(NOT_STARTED, ACTIVE, SLOW, SUSPECTED_STOP, FINISHED);
    public static final Set<String> MALOLACTIC = Set.of(NOT_STARTED, ACTIVE, SLOW, FINISHED, NOT_EXPECTED);

    private static final Map<String, String> FROM_LABEL = Map.of(
        "no iniciada", NOT_STARTED,
        "activa", ACTIVE,
        "lenta", SLOW,
        "sospecha de parada", SUSPECTED_STOP,
        "finalizada", FINISHED,
        "no prevista", NOT_EXPECTED);

    private FermentationPhase() {}

    /** Code for a code or a Spanish label; null when it is neither. */
    public static String code(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (ALCOHOLIC.contains(upper) || MALOLACTIC.contains(upper)) return upper;
        return FROM_LABEL.get(trimmed.toLowerCase(Locale.ROOT));
    }
}
