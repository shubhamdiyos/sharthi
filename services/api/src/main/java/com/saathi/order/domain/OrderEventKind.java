package com.saathi.order.domain;

/**
 * §5.5's event kinds. {@code FULFILMENT_PROGRESS} is the only kind allowed on a self-edge (T13):
 * a rider moving a job is timeline information, never a new order status (§6.5).
 */
public enum OrderEventKind {
    STATUS,
    FULFILMENT_PROGRESS,
    CONSENT,
    CANCELLATION
}
