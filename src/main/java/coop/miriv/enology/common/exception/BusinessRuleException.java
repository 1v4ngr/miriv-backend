package coop.miriv.enology.common.exception;

/**
 * Raised when a request is well-formed but violates a functional business rule
 * (for example RF-DEP-04 capacity checks or RF-MOV-02 balance validation).
 *
 * The optional {@code code} is a stable identifier (e.g. {@code CAPACITY_EXCEEDED})
 * that the front can pattern-match on without parsing the human message.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String message) {
        this(null, message);
    }

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
