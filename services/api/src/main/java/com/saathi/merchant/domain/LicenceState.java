package com.saathi.merchant.domain;

/** PRD §15's licence evidence for a licence-requiring category, read by {@code MERCHANT_APPROVE}. */
public enum LicenceState {
    NOT_SUBMITTED,
    SUBMITTED,
    VALID,
    INVALID
}
