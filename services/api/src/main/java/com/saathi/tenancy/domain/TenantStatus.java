package com.saathi.tenancy.domain;

/**
 * A city's operating state (§5.2). The list is the database's CHECK, not a preference:
 * {@code tenant_status} rejects anything else, so an enum value added here without a migration
 * is a write that fails at runtime.
 *
 * <p>Only {@code LIVE} cities are publicly visible. {@code DRAFT} is a city being configured,
 * {@code SUSPENDED} one the platform has stopped — both stay addressable for the operator's own
 * screens and for data that already exists, which is why they are states rather than deletes.
 */
public enum TenantStatus {
    LIVE,
    DRAFT,
    SUSPENDED,
    ARCHIVED
}
