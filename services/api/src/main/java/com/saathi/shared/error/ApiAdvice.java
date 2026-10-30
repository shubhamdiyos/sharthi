package com.saathi.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions onto §10.2's envelope. No handler here may echo a message it did not write. */
@Slf4j
@RestControllerAdvice
public class ApiAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorEnvelope> handleApiException(ApiException e) {
        return ResponseEntity.status(e.code().status())
            .body(ErrorEnvelope.of(e.code(), e.getMessage(), requestId(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
            .toList();
        return unprocessable(Map.of("field_errors", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolation(ConstraintViolationException e) {
        List<Map<String, String>> violations = e.getConstraintViolations().stream()
            .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", String.valueOf(v.getMessage())))
            .toList();
        return unprocessable(Map.of("field_errors", violations));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorEnvelope> handleMalformedRequest(Exception e) {
        return unprocessable(Map.of());
    }

    /**
     * An unmapped path answers 404 in the same envelope as every other error. Without this
     * handler the catch-all below turns a typo into a 500, and §10.2's claim that one shape
     * covers the API is false for the single most common client mistake.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorEnvelope> handleUnmappedPath(Exception e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
            .body(ErrorEnvelope.of(ErrorCode.NOT_FOUND, "Not found in the caller's scope.", requestId(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception e) {
        // Logged in full, answered in none: the envelope stays opaque (§10.1). A 500 that
        // leaves no trace is undiagnosable, so the trace goes to the log with the request id.
        log.error("Unhandled error for request {}", requestId(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
            .body(ErrorEnvelope.opaque(requestId()));
    }

    private ResponseEntity<ErrorEnvelope> unprocessable(Map<String, Object> details) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
            .body(ErrorEnvelope.of(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                requestId(), details));
    }

    private static String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "unknown" : id;
    }
}
