package com.saathi.order.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code Idempotency-Key} store (§14.3, §10.6.1).
 *
 * <p>The claim is an insert that may do nothing. That is deliberate: {@code ON CONFLICT DO
 * NOTHING} lets two concurrent retries find out which one owns the placement without either
 * failing the request, and the composite key starting with {@code tenant_id} is what stops one
 * city's key from claiming another's order.
 *
 * <p>{@code response_status} is null while the first request is still working; a retry that sees
 * that state is told to wait rather than being handed a half-written order.
 */
@Repository
@RequiredArgsConstructor
public class IdempotencyStore {

    /** The stored outcome: the hash that must match, and the response to replay verbatim. */
    public record Record(String requestHash, Integer responseStatus, String responseJson,
                         String resourceId) {
    }

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Optional<Record> find(String tenantId, String key, String endpoint) {
        List<Record> rows = jdbc.query("""
            SELECT request_hash, response_status, response_json, resource_id
              FROM idempotency_record
             WHERE tenant_id = ? AND idempotency_key = ? AND endpoint = ?
            """,
            (rs, rowNum) -> new Record(
                rs.getString(1),
                (Integer) rs.getObject(2),
                rs.getString(3),
                rs.getString(4)),
            tenantId, key, endpoint);
        return rows.stream().findFirst();
    }

    /** True when this request now owns the key; false when somebody else already claimed it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String tenantId, String key, String endpoint, String requestHash,
                         Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbc.update("""
            INSERT INTO idempotency_record
                (tenant_id, idempotency_key, endpoint, request_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key, endpoint) DO NOTHING
            """,
            tenantId, key, endpoint, requestHash, at, at.plusHours(24)) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String tenantId, String key, String endpoint, int responseStatus,
                         String responseJson, String resourceId, Instant now) {
        int updated = jdbc.update("""
            UPDATE idempotency_record
               SET response_status = ?, response_json = ?, resource_id = ?, completed_at = ?
             WHERE tenant_id = ? AND idempotency_key = ? AND endpoint = ?
            """,
            responseStatus, responseJson, resourceId,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            tenantId, key, endpoint);
        if (updated != 1) {
            throw new IllegalStateException("idempotency record disappeared during placement");
        }
    }
}
