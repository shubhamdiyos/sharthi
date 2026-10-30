package com.saathi.shared.tenant;

/** Who acted. Mirrors {@code order_event.actor_type} and {@code audit_event.actor_type} (§5.5, §13). */
public enum PrincipalKind {
    CUSTOMER,
    MERCHANT_STAFF,
    RIDER,
    OPERATOR,
    SUPER_ADMIN,
    SYSTEM
}
