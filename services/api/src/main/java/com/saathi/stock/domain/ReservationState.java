package com.saathi.stock.domain;

/** §7.2's reservation lifecycle. Only {@code HELD} holds quantity; the rest are history. */
public enum ReservationState {
    HELD,
    CONSUMED,
    RELEASED,
    TRUE_UP
}
