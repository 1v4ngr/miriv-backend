package coop.miriv.enology.common.exception;

import java.math.BigDecimal;

/**
 * Raised when a write operation expected a certain deposit balance but the
 * current balance has moved (RF-MOV-04 / UI15). The handler responds with
 * HTTP 409 and {@code code = "STALE_BALANCE"} plus the expected and current
 * volumes so the front can show the diff and recover user input.
 */
public class StaleBalanceException extends RuntimeException {

    private final BigDecimal expected;
    private final BigDecimal current;
    private final String depositCode;

    public StaleBalanceException(BigDecimal expected, BigDecimal current, String depositCode) {
        super("El volumen del depósito ha cambiado mientras registrabas el movimiento.");
        this.expected = expected;
        this.current = current;
        this.depositCode = depositCode;
    }

    public BigDecimal getExpected() { return expected; }
    public BigDecimal getCurrent() { return current; }
    public String getDepositCode() { return depositCode; }
}
