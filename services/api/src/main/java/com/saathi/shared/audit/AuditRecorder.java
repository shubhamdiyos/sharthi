package com.saathi.shared.audit;

/**
 * The only writer of {@code audit_event}. Business code never inserts into the table
 * directly (§3); a mutation that cannot produce an audit row must not commit.
 */
public interface AuditRecorder {

    void record(AuditEntry entry);
}
