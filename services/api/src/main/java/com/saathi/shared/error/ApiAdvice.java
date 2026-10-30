package com.saathi.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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
            .map(fe -> fieldError(wireName(fe.getField()), fe.getDefaultMessage()))
            .toList();
        return unprocessable(Map.of("field_errors", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolation(ConstraintViolationException e) {
        List<Map<String, String>> violations = e.getConstraintViolations().stream()
            .map(v -> fieldError(wireName(v.getPropertyPath().toString()), v.getMessage()))
            .toList();
        return unprocessable(Map.of("field_errors", violations));
    }

    /**
     * A rejected request parameter reaches the advice as one of two types that share an
     * interface and no base class: {@code HandlerMethodValidationException} for the built-in
     * controller route (Spring 6.1 moved it off the proxy), and {@code MethodValidationException}
     * for a service bean behind {@code @Validated}. Handling only the second — which is what the
     * class names suggest — leaves an ordinary client typo falling through to the catch-all, so a
     * 422 becomes a 500 and §10.1's promise that 5xx means the platform failed stops being true.
     * {@code HttpContractIT} covers both because only a real request shows which one fires.
     *
     * <p>The name reported is the <em>method parameter's</em> name, which this API treats as the
     * wire name: a controller that binds {@code @RequestParam(name = "q") String query} would
     * answer for a field called {@code query}. Request params are therefore declared under the
     * name the client sends.
     */
    @ExceptionHandler({MethodValidationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ErrorEnvelope> handleMethodValidation(Throwable failure) {
        MethodValidationResult result = (MethodValidationResult) failure;
        List<Map<String, String>> fieldErrors = result.getParameterValidationResults().stream()
            .flatMap(parameter -> parameter.getResolvableErrors().stream()
                .map(error -> fieldError(wireName(parameter.getMethodParameter().getParameterName()),
                    error.getDefaultMessage())))
            .toList();
        return unprocessable(Map.of("field_errors", fieldErrors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorEnvelope> handleMalformedRequest(Exception e) {
        return unprocessable(Map.of());
    }

    /**
     * 405 and 415 are the two statuses §10.2 cannot express: its list is closed and contains no
     * entry for a wrong verb or an unsupported media type. Falling through to the catch-all would
     * answer a client's typo with {@code INTERNAL_ERROR} and count it as a platform failure, so
     * these are answered with the status and no body at all — the smallest departure from the
     * envelope that does not invent a code. Raised for the founder: either §10.2 gains the two
     * entries, or the API decides never to emit 405/415 and routes everything through 422.
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Void> handleUnsupportedRequest(ErrorResponse e) {
        log.warn("Unsupported request for {}: {}", requestId(), e.getClass().getSimpleName());
        // Spring's own headers carry Allow for a wrong verb; the body stays empty because the
        // envelope would need a code §10.2 does not contain.
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).build();
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

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", String.valueOf(message));
    }

    /**
     * The wire is snake_case, so an error has to name the field the client actually sent:
     * {@code public_code}, not {@code publicCode}. Dots and bracket indices are left alone —
     * only a lower-to-upper transition is rewritten.
     */
    private static String wireName(String javaName) {
        if (javaName == null) {
            return "unknown";
        }
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "unknown" : id;
    }
}
