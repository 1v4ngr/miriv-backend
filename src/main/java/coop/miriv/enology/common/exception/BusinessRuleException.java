package coop.miriv.enology.common.exception;

/**
 * Raised when a request is well-formed but violates a functional business rule
 * (for example RF-DEP-04 capacity checks or RF-MOV-02 balance validation).
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
