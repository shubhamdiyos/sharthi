package com.saathi.stock.domain;

/** §7.2's three decrement policies. The tenant sets one; a merchant may override it. */
public enum StockPolicy {
    FLAG_ONLY,
    RESERVE_ON_PLACEMENT,
    RESERVE_ON_ACCEPT
}
