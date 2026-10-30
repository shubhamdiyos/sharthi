package com.saathi.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The single error shape every endpoint returns (§10.2):
 * {@code {"error": {"code", "message", "request_id", "details"}}}.
 *
 * <p>{@code request_id} is the same value as the {@code X-Request-Id} response header and is
 * the join key into {@code audit_event} and the structured logs (§10.1).
 */
public record ErrorEnvelope(Detail error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Detail(String code, String message, String requestId, Map<String, Object> details) {
    }

    public static ErrorEnvelope of(ErrorCode code, String message, String requestId, Map<String, Object> details) {
        return new ErrorEnvelope(new Detail(code.name(), message, requestId, details));
    }

    /** Never expose an unexpected failure's message: it can carry SQL, paths or PII. */
    public static ErrorEnvelope opaque(String requestId) {
        return of(ErrorCode.INTERNAL_ERROR, "The request could not be completed.", requestId, Map.of());
    }
}
