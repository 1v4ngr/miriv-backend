package coop.miriv.enology.security;

/** Raised when a login is attempted while the account is under the brute-force lockout. */
public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("Cuenta temporalmente bloqueada por demasiados intentos fallidos. Vuelve a intentarlo en 15 minutos.");
    }
}
