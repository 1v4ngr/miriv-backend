package coop.miriv.enology.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import coop.miriv.enology.security.AccountLockedException;
import coop.miriv.enology.security.InvalidCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into a stable, machine-readable {@link ApiError}
 * payload. Codes follow the contract in F2-02; messages are localized in
 * Spanish so the user always sees a coherent language.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String CODE_VALIDATION = "VALIDATION";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_CONFLICT = "CONFLICT";

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), null, request, List.of(), null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception.getCode(), request, List.of(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), exception.getCode(), request, List.of(), null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException exception,
                                                          HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Otro usuario ha modificado este registro. Recarga e inténtalo de nuevo.",
            CODE_CONFLICT, request, List.of(), null);
    }

    @ExceptionHandler(StaleBalanceException.class)
    public ResponseEntity<ApiError> handleStaleBalance(StaleBalanceException exception, HttpServletRequest request) {
        Map<String, Object> details = Map.of(
            "expected", exception.getExpected(),
            "current", exception.getCurrent(),
            "depositCode", exception.getDepositCode() == null ? "" : exception.getDepositCode()
        );
        return build(HttpStatus.CONFLICT, exception.getMessage(), "STALE_BALANCE", request, List.of(), details);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataConflict(DataIntegrityViolationException exception,
                                                        HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "El cambio entra en conflicto con datos existentes.", CODE_CONFLICT,
            request, List.of(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "No has iniciado sesión o tu sesión ha caducado.", "UNAUTHENTICATED",
            request, List.of(), null);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, exception.getMessage(), "INVALID_CREDENTIALS", request, List.of(),
            Map.of("attemptsRemaining", exception.getAttemptsRemaining()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiError> handleAccountLocked(AccountLockedException exception, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), "ACCOUNT_LOCKED", request, List.of(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
            ? "No tienes permiso para realizar esta acción."
            : exception.getMessage();
        return build(HttpStatus.FORBIDDEN, message, CODE_FORBIDDEN, request, List.of(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "El cuerpo de la petición no se puede interpretar.", "MALFORMED_BODY",
            request, List.of(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Hay datos no válidos.", CODE_VALIDATION, request, violations, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, String code, HttpServletRequest request,
                                            List<ApiError.FieldViolation> violations, Map<String, Object> details) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, code,
            request.getRequestURI(), violations, details);
        return ResponseEntity.status(status).body(body);
    }
}
