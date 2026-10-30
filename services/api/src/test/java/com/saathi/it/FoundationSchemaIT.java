package com.saathi.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.id.Ulid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * V1 as the database actually enforces it, on the PostgreSQL 16 the stack runs on.
 *
 * <p>The kernel's unit tests check the values the application produces; this class checks the
 * other half of the deal — that the schema refuses what the application would rather not have to
 * remember. Every constraint named here is one a hand-inserted fixture row or a future module
 * could otherwise walk straight through.
 */
class FoundationSchemaIT extends AbstractIntegrationTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);

    /** Eleven columns, eleven values, in one place: see {@link #insertAudit}. */
    private static final String INSERT_AUDIT = """
        INSERT INTO audit_event (id, tenant_id, actor_type, actor_id, action, entity, entity_id,
                                 reason_code, request_id, ip_hash, reversal_of_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String INSERT_JOB_RUN = """
        INSERT INTO job_run (id, job_key, tenant_id, run_key, business_day) VALUES (?, ?, ?, ?, ?)
        """;

    @AfterEach
    void clearTheTablesThatAreAllowedToBeCleared() {
        // audit_event is not one of them: its own triggers reject DELETE, so its rows accumulate
        // for the life of this database. Nothing below counts table totals.
        jdbc.update("DELETE FROM job_run");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void flywayAppliedTheWholeChainOnceToADatabaseItDidNotAlreadyOwn() {
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank");
        // Arrays.asList and not List.of: a repeatable migration's version column is NULL, and
        // List.of throws on a null element before the assertion ever compares anything.
        assertEquals(Arrays.asList("1", "2", "3", null),
            history.stream().map(row -> (String) row.get("version")).toList(),
            "V1, V2, V3 and the pilot seed, in that order, and nothing else");
        assertTrue(history.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))),
            "a failed step in the IT database would be the same failure in a deployment");
        // The seed is repeatable: it is applied once here, and re-applied only when its checksum
        // changes. A second row for it in one run would mean Flyway re-ran it per migration.
        assertEquals(1, history.stream().filter(row -> row.get("version") == null).count());
    }

    @Test
    void theFourFoundationTablesAreThereWithTheIndexesTheKernelQueriesBy() {
        for (String table : List.of("audit_event", "idempotency_record", "outbox_event", "job_run")) {
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table), "missing table " + table);
        }
        // Named because §10.3's keyset paging and §4.6's drainer both assume they exist; a
        // quietly missing index is a production latency incident, not a failed test.
        for (String index : List.of("audit_event_tenant_time", "outbox_pending", "job_run_day")) {
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class, index), "missing index " + index);
        }
    }

    @Test
    void auditEventRefusesUpdateDeleteAndTruncate() {
        String id = auditRow();
        assertEquals("SA001",
            refusal(() -> jdbc.update("UPDATE audit_event SET action = ? WHERE id = ?", "X", id))
                .getSQLState(), "the trigger's own code, so a caller can tell it from a check violation");
        assertEquals("SA001", refusal(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", id)).getSQLState());

        SQLException truncate = refusal(() -> jdbc.execute("TRUNCATE audit_event"));
        assertEquals("SA001", truncate.getSQLState());
        assertTrue(truncate.getMessage().contains("append-only"), truncate.getMessage());
    }

    @Test
    void theDatabasesIdCheckAgreesWithTheGenerator() {
        // The generator emits uppercase Crockford; the CHECK must reject everything else, or a
        // hand-inserted fixture row can be the lowercase twin of a real id.
        SQLException refused = refusal(() -> insertAudit(Ulid.next(CLOCK).toLowerCase(), Ulid.next(CLOCK),
            "SYSTEM", null, "A", "e", "i", null, null, null, null));
        assertEquals("23514", refused.getSQLState());
        assertTrue(refused.getMessage().contains("audit_event_id_ulid"), refused.getMessage());
    }

    @Test
    void onlyASystemActorMayCarryNoIdentity() {
        String tenant = Ulid.next(CLOCK);
        assertTrue(refusal(() -> insertAudit(Ulid.next(CLOCK), tenant, "SYSTEM", "operator-9",
            "A", "e", "i", null, null, null, null))
            .getMessage().contains("audit_event_actor_identity"),
            "SYSTEM with an actor_id credits a person with what the platform did");

        assertTrue(refusal(() -> insertAudit(Ulid.next(CLOCK), tenant, "OPERATOR", null,
            "A", "e", "i", null, null, null, null))
            .getMessage().contains("audit_event_actor_identity"),
            "an operator row that names nobody is unattributable");
    }

    @Test
    void anActorTypeOutsideTheSixIsRefused() {
        assertTrue(refusal(() -> insertAudit(Ulid.next(CLOCK), Ulid.next(CLOCK), "SUPERUSER", "x",
            "A", "e", "i", null, null, null, null))
            .getMessage().contains("audit_event_actor_type"));
    }

    @Test
    void anIpHashIsSixtyFourHexCharactersOrNothing() {
        assertTrue(refusal(() -> insertAudit(Ulid.next(CLOCK), Ulid.next(CLOCK), "SYSTEM", null,
            "A", "e", "i", null, null, "49.36.140.8", null))
            .getMessage().contains("audit_event_ip_hash_shape"),
            "a clear-text address must never fit in this column (§13.4)");
    }

    @Test
    void aReversalNamesTheRowItCorrectsAndCarriesAReason() {
        String tenant = Ulid.next(CLOCK);
        String original = Ulid.next(CLOCK);
        insertAudit(original, tenant, "SYSTEM", null, "STATE_TRANSITION", "order", "o-1",
            "INITIAL_ENTRY", Ulid.next(CLOCK), null, null);

        assertTrue(refusal(() -> insertAudit(Ulid.next(CLOCK), tenant, "SYSTEM", null, "STATE_TRANSITION",
            "order", "o-1", null, null, null, original))
            .getMessage().contains("audit_event_reversal_reason"),
            "history is amended by a row that says why, never in silence (§13.1)");

        String correction = Ulid.next(CLOCK);
        insertAudit(correction, tenant, "SYSTEM", null, "STATE_TRANSITION", "order", "o-1",
            "OPERATOR_ERROR", null, null, original);
        assertEquals(original,
            jdbc.queryForObject("SELECT reversal_of_id FROM audit_event WHERE id = ?", String.class, correction));
    }

    @Test
    void businessDayIsTheIndianDateNotTheUtcOne() {
        String id = Ulid.next(CLOCK);
        jdbc.update("""
            INSERT INTO audit_event (id, tenant_id, actor_type, action, entity, entity_id, occurred_at)
            VALUES (?, ?, 'SYSTEM', 'A', 'e', 'i', ?)
            """, id, Ulid.next(CLOCK), Timestamp.from(Instant.parse("2026-10-30T20:00:00Z")));

        // 20:00 UTC is 01:30 in Siwan, so that request belongs to the 31st's reconciliation.
        assertEquals(LocalDate.of(2026, 10, 31), businessDay(id),
            "§4.6's nightly jobs and §14's statements are keyed on this column");
    }

    @Test
    void jobKeysAreExactlyTheFiveTheDesignClosedTheListOn() {
        for (String jobKey : List.of("outbox_drain", "statement_materialisation",
            "nightly_reconciliation_assertion", "exception_scan", "backup_verification")) {
            jdbc.update(INSERT_JOB_RUN, Ulid.next(CLOCK), jobKey, Ulid.next(CLOCK), "rk",
                LocalDate.of(2026, 10, 30));
        }
        assertTrue(refusal(() -> jdbc.update(INSERT_JOB_RUN, Ulid.next(CLOCK), "auto_reject_stale_orders",
            Ulid.next(CLOCK), "rk", LocalDate.of(2026, 10, 30)))
            .getMessage().contains("job_run_key_closed_list"),
            "§4.6's list is closed: a new job is a migration and a review");
    }

    @Test
    void oneJobRunPerTenantPerBusinessDay() {
        String tenant = Ulid.next(CLOCK);
        LocalDate day = LocalDate.of(2026, 10, 30);
        jdbc.update(INSERT_JOB_RUN, Ulid.next(CLOCK), "exception_scan", tenant, "rk-1", day);

        SQLException second = refusal(() -> jdbc.update(INSERT_JOB_RUN, Ulid.next(CLOCK), "exception_scan",
            tenant, "rk-2", day));
        assertEquals("23505", second.getSQLState());
        assertTrue(second.getMessage().contains("job_run_one_per_day"), second.getMessage());

        // Same job, same day, another city: the uniqueness is per tenant, not global.
        jdbc.update(INSERT_JOB_RUN, Ulid.next(CLOCK), "exception_scan", Ulid.next(CLOCK), "rk-3", day);
    }

    @Test
    void anIdempotencyKeyIsScopedToItsTenant() {
        String tenantA = Ulid.next(CLOCK);
        String tenantB = Ulid.next(CLOCK);
        insertIdempotency(tenantA);
        insertIdempotency(tenantB);   // same key and endpoint, different city: allowed, on purpose

        SQLException replay = refusal(() -> insertIdempotency(tenantA));
        assertEquals("23505", replay.getSQLState());
        assertTrue(replay.getMessage().contains("idempotency_record_pkey"), replay.getMessage());
    }

    @Test
    void anIdempotencyRequestIsAKeyAndAnEndpointNeverAQueryString() {
        String tenant = Ulid.next(CLOCK);
        assertTrue(refusal(() -> jdbc.update(
            "INSERT INTO idempotency_record (tenant_id, idempotency_key, endpoint, request_hash) VALUES (?, ?, ?, ?)",
            tenant, "0123456789", "POST /orders?source=push", hash()))
            .getMessage().contains("idempotency_endpoint_shape"),
            "a varying query string lets a client make every key look new");

        assertTrue(refusal(() -> jdbc.update(
            "INSERT INTO idempotency_record (tenant_id, idempotency_key, endpoint, request_hash) VALUES (?, ?, ?, ?)",
            tenant, "1234567", "POST /orders", hash()))
            .getMessage().contains("idempotency_key_shape"),
            "a seven-character key is guessable");
    }

    @Test
    void anOutboxRowCannotClaimToBeDispatchedBeforeItHappened() {
        String id = Ulid.next(CLOCK);
        jdbc.update("""
            INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload,
                                      occurred_at, available_at)
            VALUES (?, ?, 'ORDER', ?, 'ORDER_DELIVERED', '{"order_id":"x"}'::jsonb, ?, ?)
            """, id, Ulid.next(CLOCK), Ulid.next(CLOCK),
            Timestamp.from(Instant.parse("2026-10-30T10:00:00Z")),
            Timestamp.from(Instant.parse("2026-10-30T10:00:00Z")));

        assertTrue(refusal(() -> jdbc.update("UPDATE outbox_event SET dispatched_at = ? WHERE id = ?",
            Timestamp.from(Instant.parse("2026-10-30T09:00:00Z")), id))
            .getMessage().contains("outbox_dispatched_after_event"),
            "a delivery recorded before the event it reports is a false receipt (§14.5)");

        assertTrue(refusal(() -> jdbc.update("""
            INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload,
                                      last_error_code)
            VALUES (?, ?, 'ORDER', ?, 'E', '{}', 'RATE_LIMITED')
            """, Ulid.next(CLOCK), Ulid.next(CLOCK), Ulid.next(CLOCK)))
            .getMessage().contains("outbox_error_only_when_attempted"),
            "a failure recorded against zero attempts did not happen");
    }

    /**
     * The eleven columns of {@code audit_event} as eleven named parameters, so a call site that
     * leaves one out is a compile error and not a {@code No value specified for parameter 11}
     * from the driver — which is what happened the first time this class was written, and which
     * turned four constraint tests into four green-looking failures about the wrong thing.
     */
    private void insertAudit(String id, String tenantId, String actorType, String actorId, String action,
                             String entity, String entityId, String reasonCode, String requestId,
                             String ipHash, String reversalOfId) {
        jdbc.update(INSERT_AUDIT, id, tenantId, actorType, actorId, action, entity, entityId,
            reasonCode, requestId, ipHash, reversalOfId);
    }

    private LocalDate businessDay(String auditEventId) {
        return jdbc.queryForObject("SELECT business_day FROM audit_event WHERE id = ?",
            (ResultSet rs, int rowNum) -> rs.getObject("business_day", LocalDate.class), auditEventId);
    }

    private void insertIdempotency(String tenant) {
        jdbc.update("""
            INSERT INTO idempotency_record (tenant_id, idempotency_key, endpoint, request_hash)
            VALUES (?, ?, ?, ?)
            """, tenant, "0123456789", "POST /orders", hash());
    }

    private static String hash() {
        return "a".repeat(64);
    }

    private String auditRow() {
        String id = Ulid.next(CLOCK);
        insertAudit(id, Ulid.next(CLOCK), "SYSTEM", null, "A", "e", "i", null, null, null, null);
        return id;
    }

    /**
     * Runs a statement that must fail and hands back the database's own answer. Spring's exception
     * translation is deliberately unwrapped: these tests are about SQLSTATE and constraint names,
     * which is what both the api container and a future psql session see.
     */
    private static SQLException refusal(Runnable statement) {
        try {
            statement.run();
        } catch (DataAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    return sql;
                }
            }
            throw new AssertionError("the database failed, but not with a SQL error", e);
        }
        throw new AssertionError("expected the database to refuse, but the statement succeeded");
    }
}
