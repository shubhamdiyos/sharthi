package com.saathi.merchant.domain;

/**
 * What a shop can physically do (§5.3). The database refuses a default mode the capability cannot
 * honour, so the two columns can never describe a shop that promises its own delivery and cannot.
 */
public enum FulfilmentCapability {
    PLATFORM_ONLY,
    MERCHANT_ONLY,
    BOTH
}
