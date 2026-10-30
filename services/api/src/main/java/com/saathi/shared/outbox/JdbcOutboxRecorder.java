package com.saathi.shared.outbox;

import com.saathi.shared.id.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox writer, deliberately MANDATORY propagation: an outbox row outside a transaction
 * would be an event that can commit while the order it describes rolls back.
 */
@Component
@RequiredArgsConstructor
public class JdbcOutboxRecorder implements OutboxRecorder {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String tenantId, String aggregateType, String aggregateId, String eventType,
                       String payloadJson) {
        Instant now = clock.instant();
        jdbc.update("""
            INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type,
                                      payload, occurred_at, available_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """,
            Ulid.next(clock), tenantId, aggregateType, aggregateId, eventType, payloadJson,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
