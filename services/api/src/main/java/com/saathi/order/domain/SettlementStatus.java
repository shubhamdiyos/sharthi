package com.saathi.order.domain;

/** §9.5's settlement lifecycle for the order's payable lines. */
public enum SettlementStatus {
    NOT_DUE,
    PENDING,
    SETTLED,
    ON_HOLD
}
