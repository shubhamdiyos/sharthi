package com.saathi.merchant.domain;

/** §5.3's merchant lifecycle. A shop that is not {@code APPROVED} is not on the storefront. */
public enum MerchantState {
    PENDING,
    APPROVED,
    SUSPENDED,
    REJECTED
}
