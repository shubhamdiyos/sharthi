package com.saathi.identity.domain;

/**
 * What an OTP is for (§12.8).
 *
 * <p>Bound into the challenge so that a code issued to log in cannot be replayed as a code that
 * changes a phone number. The purpose is part of the one-open-challenge index, so a login code and
 * a phone-change code for the same phone can coexist — and neither can stand in for the other.
 */
public enum OtpPurpose {
    LOGIN,
    REGISTRATION,
    STEP_UP,
    PHONE_CHANGE,
    PASSWORD_RESET
}
