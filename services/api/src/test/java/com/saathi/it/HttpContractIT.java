package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * System Design §10.1 and §10.2, asserted on bytes rather than on Java objects.
 *
 * <p>Everything here is deliberately driven through a socket. The filter registration, the
 * Jackson naming strategy, the {@code @RestControllerAdvice} routing and the 404-instead-of-403
 * rule are all properties of the assembled application, and each of them can be unit-tested green
 * while the running service answers something else.
 *
 * <p>One ULID is hardcoded below instead of generated: it is the value a client submits, so the
 * assertion is about the platform honouring it, not about two random strings matching.
 */
class HttpContractIT extends AbstractIntegrationTest {

    private static final String CLIENT_REQUEST_ID = "01JZC4WQ8S2N7F5K9M3H6T0R1V";
    private static final String OPAQUE_MESSAGE = "The request could not be completed.";

    @Test
    @DisplayName("a successful call speaks snake_case and carries a minted request id")
    void aSuccessfulCallSpeaksSnakeCaseAndCarriesAMintedRequestId() {
        HttpResponse<String> response = get("/it-probe/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(Ulid.isValid(requestId)).as("§10.1: every response carries a well-formed id").isTrue();

        JsonNode json = body(response);
        assertThat(json.propertyNames()).containsExactly("probe_ok", "request_id");
        assertThat(json.get("probe_ok").booleanValue()).isTrue();
        assertThat(json.get("request_id").stringValue()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("a well-formed client request id is honoured end to end")
    void aWellFormedClientRequestIdIsHonoured() {
        HttpResponse<String> response = get("/it-probe/ping", CLIENT_REQUEST_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).contains(CLIENT_REQUEST_ID);
        assertThat(body(response).get("request_id").stringValue()).isEqualTo(CLIENT_REQUEST_ID);
    }

    @Test
    @DisplayName("a malformed client request id is replaced, never echoed")
    void aMalformedClientRequestIdIsReplaced() {
        String injection = "'; DROP TABLE audit_event;--";
        HttpResponse<String> response = get("/it-probe/ping", injection);

        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(requestId).isNotEqualTo(injection);
        assertThat(Ulid.isValid(requestId)).as("the header cannot inject text into a log line").isTrue();
        assertThat(response.body()).doesNotContain("DROP TABLE");
    }

    @Test
    @DisplayName("an error repeats the request id its header carries")
    void anErrorRepeatsTheHeaderSRequestId() {
        HttpResponse<String> response = get("/it-probe/not-found");

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode error = error(response);
        assertThat(error.get("request_id").stringValue())
            .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
    }

    @Test
    @DisplayName("the envelope is exactly error{code, message, request_id}")
    void theEnvelopeIsTheDocumentedShape() {
        HttpResponse<String> response = get("/it-probe/not-found");

        assertThat(body(response).propertyNames()).containsExactly("error");
        assertThat(error(response).propertyNames()).containsExactlyInAnyOrder("code", "message", "request_id");
    }

    @Test
    @DisplayName("403 FORBIDDEN: a permission the principal does not hold")
    void aMissingPermissionIsForbidden() {
        HttpResponse<String> response = get("/it-probe/forbidden");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").stringValue()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("404 NOT_FOUND: another tenant's row, never 403 (§4.3-5)")
    void aCrossTenantReadIsNotFoundNotForbidden() {
        HttpResponse<String> response = get("/it-probe/cross-tenant");

        // The status is the whole point: 403 would confirm the row exists somewhere else.
        assertThat(response.statusCode()).as("§4.3-5").isNotEqualTo(403);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("403 TENANT_SCOPE_VIOLATION: granted permission, refused scope, no resource named")
    void aRefusedScopeIsACodeOfItsOwn() {
        HttpResponse<String> response = get("/it-probe/scope-violation");

        assertThat(response.statusCode()).isEqualTo(403);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("TENANT_SCOPE_VIOLATION");
        assertThat(error.get("message").stringValue()).doesNotContain("01", "tenant", "order");
    }

    @Test
    @DisplayName("409 INVALID_TRANSITION: a state machine refusal is not a validation failure")
    void anIllegalTransitionIsAConflict() {
        HttpResponse<String> response = get("/it-probe/invalid-transition");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(error(response).get("code").stringValue()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    @DisplayName("422 on a body names the wire fields, not the Java components")
    void aRejectedBodyNamesTheFieldsTheClientSent() {
        HttpResponse<String> response = post("/it-probe/orders", """
            {"public_code": "", "line_count": 0}""");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        // publicCode is the component's name. A client that sent "public_code" and is told the
        // problem is "publicCode" has to guess that the two are the same field.
        assertThat(rejectedFields(error)).containsExactly("line_count", "public_code");
        error.get("details").get("field_errors").valueStream().forEach(fe ->
            assertThat(fe.get("message").stringValue()).isNotBlank());
    }

    @Test
    @DisplayName("422 on a query parameter produces the same body shape")
    void aRejectedQueryParamUsesTheSameShape() {
        HttpResponse<String> response = get("/it-probe/validated?q=&limit=0");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");

        assertThat(rejectedFields(error)).containsExactly("limit", "q");
    }

    @Test
    @DisplayName("a body that is not JSON is 422 with no field list")
    void anUnparseableBodyIsRejectedWithoutDetails() {
        HttpResponse<String> response = post("/it-probe/orders", "public_code=SW-1");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.has("details")).as("nothing was parsed, so there is nothing to report per field").isFalse();
    }

    @Test
    @DisplayName("a server fault answers nothing it did not write")
    void anUnexpectedFailureIsOpaque() {
        HttpResponse<String> response = get("/it-probe/boom");

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.get("message").stringValue()).isEqualTo(OPAQUE_MESSAGE);
        // The probe's exception message is a jdbc url carrying a password. If any part of an
        // unexpected failure reached the client, this is where it would show (§10.1).
        assertThat(response.body())
            .doesNotContain("hunter2", "jdbc", "postgresql", "postgres", "IllegalStateException");
    }

    @Test
    @DisplayName("an unmapped path is 404 in the envelope, not 500")
    void anUnmappedPathIsNotFound() {
        HttpResponse<String> response = get("/it-probe/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("a wrong verb is 405 with no envelope — §10.2 has no code for it")
    void aWrongVerbIsNotAnInternalError() {
        HttpResponse<String> response = delete("/it-probe/ping");

        // INTERNAL_ERROR here would be a lie about who broke: the client used the wrong method,
        // and §10.1 counts 5xx as a platform failure. The empty body is the documented gap, not
        // an invented code.
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Allow").orElseThrow()).contains("GET");
    }

    @Test
    @DisplayName("an unsupported media type is 415 with no envelope — the same §10.2 gap")
    void anUnsupportedMediaTypeIsNotAnInternalError() {
        HttpResponse<String> response = post("/it-probe/orders", "text/plain", "public_code=SW-1");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).isEmpty();
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }

    /** The {@code field} values under details.field_errors, sorted so the assertion is order-free. */
    private List<String> rejectedFields(JsonNode error) {
        return error.get("details").get("field_errors").valueStream()
            .map(fe -> fe.get("field").stringValue())
            .sorted()
            .toList();
    }
}
