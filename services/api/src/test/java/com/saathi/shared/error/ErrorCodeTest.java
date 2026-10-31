package com.saathi.shared.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §10.2 is a closed table, so the enum is checked against it rather than trusted: a code that
 * drifts to a different status changes the API contract without changing a route.
 */
class ErrorCodeTest {

    @Test
    void theStatusesMatchTheTable() {
        assertEquals(422, ErrorCode.VALIDATION_FAILED.statusCode());
        assertEquals(401, ErrorCode.UNAUTHENTICATED.statusCode());
        assertEquals(403, ErrorCode.FORBIDDEN.statusCode());
        assertEquals(403, ErrorCode.TENANT_SCOPE_VIOLATION.statusCode());
        assertEquals(404, ErrorCode.NOT_FOUND.statusCode());
        assertEquals(409, ErrorCode.INVALID_TRANSITION.statusCode());
        assertEquals(409, ErrorCode.QUOTE_STALE.statusCode());
        assertEquals(409, ErrorCode.PRICE_CONFIRMATION_REQUIRED.statusCode());
        assertEquals(409, ErrorCode.STOCK_CHANGED.statusCode());
        assertEquals(409, ErrorCode.FULFILMENT_LOCKED.statusCode());
        assertEquals(409, ErrorCode.MODE_NOT_AVAILABLE.statusCode());
        assertEquals(422, ErrorCode.WEIGHT_OUT_OF_TOLERANCE.statusCode());
        assertEquals(409, ErrorCode.MIXED_MERCHANT_CART.statusCode());
        assertEquals(429, ErrorCode.OTP_THROTTLED.statusCode());
        assertEquals(423, ErrorCode.ACCOUNT_LOCKED.statusCode());
        assertEquals(401, ErrorCode.OTP_INVALID.statusCode());
        assertEquals(403, ErrorCode.ACCOUNT_DEACTIVATED.statusCode());
        assertEquals(403, ErrorCode.STEP_UP_REQUIRED.statusCode());
        assertEquals(403, ErrorCode.SETTLEMENT_SELF_APPROVAL.statusCode());
        assertEquals(422, ErrorCode.RATE_NOT_APPROVED.statusCode());
        assertEquals(500, ErrorCode.INTERNAL_ERROR.statusCode());
    }

    @Test
    void everyCodeIsOneOfTheSixTheApiIsAllowedToSend() {
        // Anything else (a 200, a 3xx, a 503) would either pretend success or invite a retry the
        // platform has no idempotency story for.
        Set<Integer> allowed = Set.of(401, 403, 404, 409, 422, 423, 429, 500);
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(allowed.contains(code.statusCode()), code + " -> " + code.statusCode());
        }
    }

    @Test
    void theOnlyCodeOutsideTheTableIsTheOneDocumentedAsAGap() {
        List<ErrorCode> fiveOhOnes = java.util.Arrays.stream(ErrorCode.values())
            .filter(c -> c.statusCode() >= 500).toList();
        assertEquals(List.of(ErrorCode.INTERNAL_ERROR), fiveOhOnes,
            "§10.2 has no unhandled-exception row; INTERNAL_ERROR is flagged to the founder");
    }

    @Test
    void theTableIsNotSilentlyGrowing() {
        // 39 codes in §10.2, plus the flagged INTERNAL_ERROR gap, plus the two a staff login
        // cannot be answered without, plus the three the merchant/catalogue endpoints name in
        // their own §10.5.6 / §10.5.8 error columns, plus the three §10.5.6's order-lifecycle
        // endpoints name. Adding a member is a change to the API contract, and this assertion is
        // where that has to be acknowledged first.
        assertEquals(48, ErrorCode.values().length,
            "§10.2 lists 39; INTERNAL_ERROR, INVALID_CREDENTIALS and MFA_REQUIRED are the flagged"
                + " departures documented on the enum; CATEGORY_NOT_ACTIVE, LICENCE_INCOMPLETE and"
                + " NEGATIVE_STOCK are named by §10.5.6/§10.5.8; and WEIGHT_OUT_OF_TOLERANCE,"
                + " FULFILMENT_LOCKED and MODE_NOT_AVAILABLE are named by §10.5.6's lifecycle"
                + " endpoints. Each addition is documented on the enum itself.");
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code.name(), code.name().toUpperCase(), code + " is SCREAMING_SNAKE");
            assertTrue(code.name().matches("[A-Z][A-Z0-9_]*"), code.name());
        }
    }

    @Test
    void theEnvelopeCarriesExactlyTheFourKeysAndNoStackTrace() {
        ApiException failure = new ApiException(ErrorCode.STOCK_CHANGED,
            "Two lines changed since the quote.", Map.of("quote_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        ErrorEnvelope.Detail detail = ErrorEnvelope
            .of(failure.code(), failure.getMessage(), "01ARZ3NDEKTSV4RRFFQ69G5FAW", failure.details()).error();

        assertEquals("STOCK_CHANGED", detail.code(), "the wire value is the enum name, never an ordinal");
        assertEquals("Two lines changed since the quote.", detail.message());
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAW", detail.requestId());
        assertEquals(Map.of("quote_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV"), detail.details());
    }

    @Test
    void anUnexpectedFailureSaysNothingItDidNotWrite() {
        ErrorEnvelope.Detail opaque = ErrorEnvelope.opaque("01ARZ3NDEKTSV4RRFFQ69G5FAX").error();
        assertEquals("INTERNAL_ERROR", opaque.code());
        assertEquals("The request could not be completed.", opaque.message());
        assertTrue(opaque.details() == null || opaque.details().isEmpty(), "no echo of the cause");
        assertFalse(opaque.message().contains("null"), opaque.message());
    }

    @Test
    void anApiExceptionWithoutDetailsIsNotANullMap() {
        ApiException bare = new ApiException(ErrorCode.NOT_FOUND, "Not found in the caller's scope.");
        assertEquals(Map.of(), bare.details());
        assertNull(bare.getCause(), "a rejection is not a failure of something else");
        assertTrue(bare instanceof RuntimeException, "thrown, not returned");
    }

    @Test
    void detailsAreCopiedSoACallerCannotRewriteAResponseAfterTheFact() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("allowed_actions", List.of("ACCEPT"));
        ApiException e = new ApiException(ErrorCode.INVALID_TRANSITION, "No.", mutable);
        mutable.put("allowed_actions", List.of("ACCEPT", "REJECT", "DELETE_EVERYTHING"));
        assertEquals(List.of("ACCEPT"), e.details().get("allowed_actions"));
        assertThrows(UnsupportedOperationException.class, () -> e.details().put("x", "y"));
    }
}
