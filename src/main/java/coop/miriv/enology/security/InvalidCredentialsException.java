package coop.miriv.enology.security;

/**
 * Raised on a failed login (wrong username/password) instead of letting the generic
 * Spring Security {@code AuthenticationException} escape, so the response can carry
 * how many attempts remain before the account locks (C2 of the F1/F2 review).
 */
public class InvalidCredentialsException extends RuntimeException {

    private final int attemptsRemaining;

    public InvalidCredentialsException(int attemptsRemaining) {
        super("Usuario o contraseña incorrectos.");
        this.attemptsRemaining = attemptsRemaining;
    }

    public int getAttemptsRemaining() {
        return attemptsRemaining;
    }
}
