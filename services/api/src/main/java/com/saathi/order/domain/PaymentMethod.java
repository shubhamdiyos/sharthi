package com.saathi.order.domain;

/** How the customer will pay. {@code ONLINE} is disabled for the pilot (§10.4 flags). */
public enum PaymentMethod {
    COD,
    ONLINE,
    OFFLINE_RECORD
}
