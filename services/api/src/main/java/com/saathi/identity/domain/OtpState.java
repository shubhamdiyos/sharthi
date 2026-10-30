package com.saathi.identity.domain;

/**
 * Where a challenge has got to (§12.8).
 *
 * <p>{@code EXPIRED} and {@code FAILED} are stored rather than derived because the difference is
 * what a support conversation needs: "your code timed out" and "you ran out of attempts" are the
 * same-looking refusal with different remedies. {@code CONSUMED} is what makes a code single-use —
 * a second verify of the same id answers {@code OTP_ALREADY_CONSUMED}, not a fresh session.
 *
 * <p>Only one {@code OPEN} challenge may exist per phone per purpose; the partial unique index in
 * V2 enforces it, so a request for a new code expires the previous one before inserting.
 */
public enum OtpState {
    OPEN,
    CONSUMED,
    EXPIRED,
    FAILED
}
