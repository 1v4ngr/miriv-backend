package coop.miriv.enology.common.exception;

/**
 * Raised on 409 conflicts (e.g. duplicate unique keys, optimistic locking).
 * Carries an optional stable {@code code} so the front can react without
 * parsing the message text.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
