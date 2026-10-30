package com.saathi.identity.service;

import com.saathi.identity.domain.UserAccount;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.Map;

/**
 * The result of trying a password, as a value.
 *
 * <p>{@link PasswordVerifier} has to record the failure <em>and</em> refuse. Same reason as
 * {@link com.saathi.identity.otp.OtpFailure}: a throw inside that transaction would roll back the
 * counter that §12.8's six-failure lockout is computed from, and the lockout would then never
 * happen. The caller raises the failure once the write has committed.
 *
 * @param account the row that was tried, or null when no account was named — a response must not
 *                distinguish "no such email" from "wrong password", so neither does this record
 * @param failure what the attempt means
 */
public record PasswordVerification(UserAccount account, Failure failure) {

    public boolean ok() {
        return failure == Failure.NONE;
    }

    public void throwIfFailed() {
        failure.raise(account);
    }

    public enum Failure {
        NONE(null, null),
        BAD_CREDENTIALS(ErrorCode.INVALID_CREDENTIALS, "Email or password is not right."),
        LOCKED(ErrorCode.ACCOUNT_LOCKED, "This account is locked. Try again later."),
        DEACTIVATED(ErrorCode.ACCOUNT_DEACTIVATED, "This account is deactivated.");

        private final ErrorCode code;
        private final String message;

        Failure(ErrorCode code, String message) {
            this.code = code;
            this.message = message;
        }

        private void raise(UserAccount account) {
            if (this == NONE) {
                return;
            }
            // §12.8 refuses a silent permanent lock, so the response names the moment the lock ends;
            // the client formats it, because the principal's city is not this class's to assume.
            if (this == LOCKED && account != null && account.getLockedUntil() != null) {
                throw new ApiException(code, message, Map.of("locked_until", account.getLockedUntil().toString()));
            }
            throw new ApiException(code, message);
        }
    }
}
