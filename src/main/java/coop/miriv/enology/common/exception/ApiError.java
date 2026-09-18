package coop.miriv.enology.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire error payload returned for every non-2xx response.
 *
 * {@code code} is a stable, machine-readable identifier (uppercase, snake_case)
 * that the front can pattern-match on; {@code message} is the human-readable
 * localized message. {@code details} carries optional extra fields (for example
 * expected/current volumes for {@code STALE_BALANCE}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String message,
    String code,
    String path,
    List<FieldViolation> violations,
    Map<String, Object> details
) {

    public record FieldViolation(String field, String message) {
    }
}
