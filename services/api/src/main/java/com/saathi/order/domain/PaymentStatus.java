package com.saathi.order.domain;

/** Payment state, separate from order state: a delivered order can still be unpaid. */
public enum PaymentStatus {
    UNPAID,
    PARTIAL,
    PAID,
    REFUNDED
}
