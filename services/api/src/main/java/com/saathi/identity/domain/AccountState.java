package com.saathi.identity.domain;

/**
 * Whether an account may be used right now (§5.1).
 *
 * <p>Locked and deactivated answer <em>different</em> codes, and only to the person who owns the
 * account: §12.2's rule is {@code 423 ACCOUNT_LOCKED} / {@code 403 ACCOUNT_DEACTIVATED} to the
 * owner and {@code 401 INVALID_CREDENTIALS} to everyone else, so that a failed login never tells
 * an attacker which phone numbers belong to real people.
 */
public enum AccountState {
    ACTIVE,
    LOCKED,
    DEACTIVATED
}
