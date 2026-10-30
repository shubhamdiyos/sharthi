package com.saathi.shared.audit;

import com.saathi.shared.id.Ulid;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code audit_event}. It participates in the caller's transaction
 * ({@code MANDATORY}): an audit row written after the commit is evidence that can be lost,
 * and §13.1 does not allow that.
 *
 * <p>The table itself is append-only at the database level — a trigger rejects UPDATE and
 * DELETE — so "correct the record" means a new row with {@code reversal_of_id}, which the
 * identity and ledger modules add when they need it.
 */
@Repository
@RequiredArgsConstructor
public class JdbcAuditRecorder implements AuditRecorder {

    private static final String INSERT = """
        INSERT INTO audit_event
            (id, tenant_id, actor_type, actor_id, action, entity, entity_id,
             before_json, after_json, reason_code, request_id, ip_hash, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        entry.requireReasonCode();
        String id = entry.id() != null ? entry.id() : Ulid.next(clock);
        Instant occurredAt = entry.occurredAt() != null ? entry.occurredAt() : clock.instant();
        jdbc.update(INSERT,
            id,
            entry.tenantId(),
            entry.actorType().name(),
            entry.actorId(),
            entry.action(),
            entry.entity(),
            entry.entityId(),
            entry.beforeJson(),
            entry.afterJson(),
            entry.reasonCode(),
            entry.requestId(),
            entry.ipHash(),
            Timestamp.from(occurredAt));
    }
}
