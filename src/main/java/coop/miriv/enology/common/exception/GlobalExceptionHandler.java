package coop.miriv.enology.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({ConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConflict(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataConflict(DataIntegrityViolationException exception,
                                                        HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "The requested change conflicts with existing data.", request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Access denied.", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed.", request, violations);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request,
                                            List<ApiError.FieldViolation> violations) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message,
            request.getRequestURI(), violations);
        return ResponseEntity.status(status).body(body);
    }
}
