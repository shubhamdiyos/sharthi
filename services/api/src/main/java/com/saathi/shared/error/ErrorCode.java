package com.saathi.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The closed error-code list from System Design §10.2. A code not in this table is a change
 * to the API contract and belongs in a review, not in a controller.
 *
 * <p>§10.2 names statuses by number. {@code UNPROCESSABLE_CONTENT} is that table's 422 —
 * Spring deprecated the older constant's name, not the status code.
 *
 * <p><b>Open document gap, and the two codes that stopped being waitable.</b> §10.2 declares
 * itself closed with 39 codes, and this enum implements that list. §10.5's endpoint index
 * references 39 further codes that appear nowhere in §10.2 — {@code WEAK_PASSWORD},
 * {@code PASSWORD_REUSED}, {@code PHONE_TAKEN}, {@code FILE_TOO_LARGE}, {@code NEGATIVE_STOCK},
 * {@code WEIGHT_OUT_OF_TOLERANCE}, {@code INSUFFICIENT_CUSTODY}, {@code GRANT_ESCALATION} and
 * others. Those are deliberately <em>not</em> invented here: two callers choosing between
 * neighbouring codes is how §12.4's "identical messaging for every cause" stops being true.
 * Raised for the founder to either add them to §10.2 or replace them in §10.5.
 *
 * <p>{@code INVALID_CREDENTIALS} and {@code MFA_REQUIRED} were added anyway, because §12.1 makes
 * every staff sign-in password-then-OTP and no code in §10.2 can say either "that password is
 * wrong" or "the password was right, now the second factor". {@code UNAUTHENTICATED} would be true
 * of both and useful for neither; {@code OTP_INVALID} would be a lie about the first. The
 * merchant/catalogue module then added {@code CATEGORY_NOT_ACTIVE}, {@code LICENCE_INCOMPLETE} and
 * {@code NEGATIVE_STOCK}, and the order-lifecycle module {@code WEIGHT_OUT_OF_TOLERANCE},
 * {@code FULFILMENT_LOCKED} and {@code MODE_NOT_AVAILABLE}, each named by an endpoint's own error
 * column in §10.5.6. Those nine are the only members here that are not §10.2's list, and each is
 * the smallest answer an endpoint that exists cannot be written without. Asking what an endpoint
 * should return instead is a question for §10.2; refusing to answer at all is not an option the
 * API can offer.
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
    /**
     * Referenced by §10.5.6 and §10.5.8 and absent from §10.2's table. Added here because the
     * merchant/catalogue module is the first one that has to answer with them: a city has not
     * switched the category on, a licence-requiring category has no valid licence yet, or a
     * stock adjustment would take a count below zero. The founder was told §10.2's list is short
     * of the codes §10.5 uses; these three are the ones an endpoint in this module names.
     */
    CATEGORY_NOT_ACTIVE(HttpStatus.CONFLICT),
    LICENCE_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT),
    NEGATIVE_STOCK(HttpStatus.UNPROCESSABLE_CONTENT),
    /**
     * The order-lifecycle trio, each named in §10.5.6's error column and none of them in §10.2's
     * table. {@code WEIGHT_OUT_OF_TOLERANCE} is 422 because §10.6.3 says so: the weighed quantity
     * is well-formed input the shop is not allowed to submit, which is a different refusal from a
     * transition that is merely too early.
     */
    WEIGHT_OUT_OF_TOLERANCE(HttpStatus.UNPROCESSABLE_CONTENT),
    FULFILMENT_LOCKED(HttpStatus.CONFLICT),
    MODE_NOT_AVAILABLE(HttpStatus.CONFLICT),
    // Not §10.2's list either — see the note above. These two are the staff-login pair.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    MFA_REQUIRED(HttpStatus.UNAUTHORIZED),
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
