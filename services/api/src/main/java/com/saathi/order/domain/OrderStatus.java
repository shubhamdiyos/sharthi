package com.saathi.order.domain;

/**
 * §6.1's closed status list. Clients never send one of these: they send an action and the server
 * decides the edge (§10.7), which is why the enum lives beside the transition guard rather than in
 * the web layer.
 */
public enum OrderStatus {
    CREATED,
    PENDING_CONFIRMATION,
    MERCHANT_ACCEPTED,
    PRICE_CONFIRMATION_PENDING,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    REJECTED,
    CANCELLED,
    DELIVERY_FAILED
}
