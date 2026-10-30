package com.saathi.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The closed error-code list from System Design §10.2. A code not in this table is a change
 * to the API contract and belongs in a review, not in a controller.
 *
 * <p>§10.2 names statuses by number. {@code UNPROCESSABLE_CONTENT} is that table's 422 —
 * Spring deprecated the older constant's name, not the status code.
 *
 * <p><b>Open document gap.</b> §10.2 declares itself closed with 39 codes, and this enum
 * implements exactly those 39. §10.6's endpoint index, however, references 39 further codes
 * that appear nowhere in §10.2 — {@code INVALID_CREDENTIALS}, {@code MFA_REQUIRED},
 * {@code WEAK_PASSWORD}, {@code PASSWORD_REUSED}, {@code PHONE_TAKEN}, {@code FILE_TOO_LARGE},
 * {@code NEGATIVE_STOCK}, {@code WEIGHT_OUT_OF_TOLERANCE}, {@code INSUFFICIENT_CUSTODY},
 * {@code GRANT_ESCALATION} and others. They are deliberately <em>not</em> invented here: two
 * callers choosing between {@code INVALID_CREDENTIALS} and {@code OTP_INVALID} is how §12.4's
 * "identical messaging for every cause" stops being true. Raised for the founder to either add
 * them to §10.2 or replace them in §10.6.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    TENANT_SCOPE_VIOLATION(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_TRANSITION(HttpStatus.CONFLICT),
    MODE_FORBIDS_TRANSITION(HttpStatus.CONFLICT),
    MODE_FORBIDS_JOB(HttpStatus.CONFLICT),
    MIXED_MERCHANT_CART(HttpStatus.CONFLICT),
    OUTSIDE_SERVICE_AREA(HttpStatus.CONFLICT),
    WARD_NOT_SERVED(HttpStatus.CONFLICT),
    TENANT_NOT_LIVE(HttpStatus.CONFLICT),
    MERCHANT_UNAVAILABLE(HttpStatus.CONFLICT),
    PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT),
    STOCK_CHANGED(HttpStatus.CONFLICT),
    PRICE_CHANGED(HttpStatus.CONFLICT),
    MIN_ORDER_NOT_MET(HttpStatus.CONFLICT),
    MAX_QTY_EXCEEDED(HttpStatus.CONFLICT),
    QUOTE_STALE(HttpStatus.CONFLICT),
    PRICE_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT),
    DUPLICATE_SUBMISSION(HttpStatus.CONFLICT),
    RIDER_CASH_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    RIDER_OFFLINE(HttpStatus.CONFLICT),
    RIDER_SUSPENDED(HttpStatus.CONFLICT),
    JOB_TAKEN(HttpStatus.CONFLICT),
    OFFER_NOT_FOR_YOU(HttpStatus.CONFLICT),
    CASH_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    SETTLEMENT_SELF_APPROVAL(HttpStatus.FORBIDDEN),
    PAYOUT_RULE_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_NOT_APPROVED(HttpStatus.UNPROCESSABLE_CONTENT),
    CATEGORY_IN_USE(HttpStatus.CONFLICT),
    OTP_INVALID(HttpStatus.UNAUTHORIZED),
    OTP_EXPIRED(HttpStatus.UNAUTHORIZED),
    OTP_ALREADY_CONSUMED(HttpStatus.UNAUTHORIZED),
    OTP_THROTTLED(HttpStatus.TOO_MANY_REQUESTS),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    ACCOUNT_DEACTIVATED(HttpStatus.FORBIDDEN),
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN),
    CONFLICT(HttpStatus.CONFLICT),

    /**
     * Not in §10.2 — that table has no unhandled-exception entry, so an unexpected failure
     * had no defined shape. Raised only as 500 with no message detail, because leaking a stack
     * trace is a disclosure bug. Flagged to the founder as a document gap.
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public int statusCode() {
        return status.value();
    }
}
