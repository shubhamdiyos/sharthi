package com.saathi.identity.otp;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;

/**
 * Why a code was refused, as a value rather than an exception.
 *
 * <p>{@link OtpService#verify} has to write the attempt counter and the new state <em>and</em>
 * answer a refusal. Throwing from inside that transaction rolls the write back with it, which would
 * silently delete §12.8's five-attempt cap: every wrong code would leave {@code attempts} at zero,
 * forever. Returning this instead lets the transaction commit the evidence and the caller — outside
 * any transaction — turn it into a response.
 *
 * <p>The messages are §10.2's, and three of these seven failures share one string on purpose: an
 * unknown id, a code issued for another phone and a mistyped code all answer "That code is not
 * right.", because the first two are facts about the platform an attacker should not get to confirm.
 */
public enum OtpFailure {
    NONE(null, null),
    UNKNOWN_CODE(ErrorCode.OTP_INVALID, "That code is not right."),
    WRONG_TARGET(ErrorCode.OTP_INVALID, "That code is not right."),
    ALREADY_CONSUMED(ErrorCode.OTP_ALREADY_CONSUMED, "That code has already been used."),
    EXPIRED(ErrorCode.OTP_EXPIRED, "That code has expired."),
    ATTEMPTS_EXHAUSTED(ErrorCode.OTP_INVALID, "Too many wrong attempts for that code."),
    WRONG_CODE(ErrorCode.OTP_INVALID, "That code is not right.");

    private final ErrorCode code;
    private final String message;

    OtpFailure(ErrorCode code, String message) {
        this.code = code;
        this.message = message;
    }

    public boolean matched() {
        return this == NONE;
    }

    /** No-op on success, so a caller can write {@code verify(...).throwIfFailed()} inline. */
    public void throwIfFailed() {
        if (this != NONE) {
            throw new ApiException(code, message);
        }
    }
}
