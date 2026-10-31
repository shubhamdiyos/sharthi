package com.saathi.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.audit.AuditEntry;
import com.saathi.shared.audit.AuditRecorder;
import com.saathi.shared.audit.IpHasher;
import com.saathi.shared.config.SaathiProperties;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.PrincipalKind;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §13.1 in three sentences: an audit row is written inside the transaction that changed the
 * thing, a rollback therefore erases both or neither, and a ⚑ action without a reason code never
 * reaches the table at all.
 *
 * <p>This needs a real database. Whether {@code Propagation.MANDATORY} actually refuses an outside
 * caller, and whether the row really disappears on rollback, are properties of the transaction
 * manager and the connection, and a unit test can only assert that the annotation is present.
 */
class AuditTrailIT extends AbstractIntegrationTest {

    private static final Clock UTC = Clock.system(ZoneOffset.UTC);

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SaathiProperties properties;

    @Test
    void theRecorderRefusesToWriteOutsideATransaction() {
        assertThrows(IllegalTransactionStateException.class,
            () -> recorder.record(entry("STATE_TRANSITION", null)),
            "an audit row written after the commit is evidence that can be lost");
    }

    @Test
    void aCommittedChangeKeepsItsRow() {
        String id = Ulid.next(UTC);
        transactions.executeWithoutResult(status -> recorder.record(entry("STATE_TRANSITION", id)));

        assertEquals(1, countRows(id), "the row commits with the change it describes");
    }

    @Test
    void aRolledBackChangeLeavesNoRowBehind() {
        String id = Ulid.next(UTC);
        transactions.executeWithoutResult(status -> {
            recorder.record(entry("STATE_TRANSITION", id));
            status.setRollbackOnly();
        });

        assertEquals(0, countRows(id),
            "§13.1: a write either commits with its audit row or does not commit — never half");
    }

    @Test
    void aFlaggedActionWithoutAReasonIsRefusedBeforeItReachesTheTable() {
        String id = Ulid.next(UTC);
        // The refusal is our own IllegalArgumentException, but a @Repository bean is proxied for
        // exception translation, so what escapes the recorder is a DataAccessException carrying it.
        // Asserting the raw type instead would mean changing the kernel to satisfy the test.
        DataAccessException refused = assertThrows(DataAccessException.class,
            () -> transactions.executeWithoutResult(status -> recorder.record(entry("GRANT_ADDED", id))));

        Throwable cause = refused.getRootCause();
        assertEquals(IllegalArgumentException.class, cause.getClass(),
            "the platform's own refusal, not a database error: nothing was ever sent");
        assertTrue(cause.getMessage().contains("GRANT_ADDED"), cause.getMessage());
        assertTrue(cause.getMessage().contains("§13.2"), cause.getMessage());
        assertEquals(0, countRows(id), "and the transaction it was written into is gone too");
    }

    @Test
    void theRowCarriesTheRequestTheAddressAndBothSnapshots() {
        String id = Ulid.next(UTC);
        String tenant = Ulid.next(UTC);
        String requestId = Ulid.next(UTC);
        LocalDate day = LocalDate.of(2026, 10, 30);
        String ipHash = IpHasher.hash("49.36.140.8", properties.auditIpKey(), day);
        Instant occurredAt = Instant.parse("2026-10-30T20:00:00Z");

        transactions.executeWithoutResult(status -> recorder.record(AuditEntry.builder()
            .id(id)
            .tenantId(tenant)
            .actorType(PrincipalKind.OPERATOR)
            .actorId("operator-1")
            .action("SHORTFALL_WRITTEN_OFF")
            .entity("order")
            .entityId("SAATH-4821")
            .beforeJson("""
                {"outstanding_paise": 6700}
                """)
            .afterJson("""
                {"outstanding_paise": 0, "write_off_reason": "customer_paid_by_cash"}
                """)
            .reasonCode("COD_SHORTFALL_RESOLVED")
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(occurredAt)
            .build()));

        var row = jdbc.queryForMap("SELECT * FROM audit_event WHERE id = ?", id);
        assertEquals(tenant, row.get("tenant_id"));
        assertEquals("OPERATOR", row.get("actor_type"));
        assertEquals("operator-1", row.get("actor_id"));
        assertEquals(requestId, row.get("request_id"));
        assertEquals(ipHash, row.get("ip_hash"));
        assertEquals("COD_SHORTFALL_RESOLVED", row.get("reason_code"));
        // The driver hands a date column back as java.sql.Date, so the comparison is on the
        // LocalDate it carries rather than on the object identity it would never have.
        assertEquals(LocalDate.of(2026, 10, 31), ((java.sql.Date) row.get("business_day")).toLocalDate(),
            "the row lands on the Indian day, not the UTC one (§4.6)");
        assertEquals(occurredAt, ((java.sql.Timestamp) row.get("occurred_at")).toInstant());

        // A reviewer of this row reads two snapshots, so the row that has none is a bug; the
        // driver hands jsonb back as PGobject, so the type is asserted with the content.
        String after = jsonb(row.get("after_json"));
        assertTrue(after.contains("write_off_reason"), after);
        assertTrue(jsonb(row.get("before_json")).contains("6700"));
    }

    private static String jsonb(Object column) {
        assertNotNull(column, "a snapshot column that is null is not a snapshot (§13.3)");
        PGobject value = (PGobject) column;
        assertEquals("jsonb", value.getType());
        return value.getValue();
    }

    private int countRows(String id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private static AuditEntry entry(String action, String id) {
        return AuditEntry.builder()
            .id(id)
            .tenantId(Ulid.next(UTC))
            .actorType(PrincipalKind.SYSTEM)
            .action(action)
            .entity("order")
            .entityId("SAATH-0001")
            .build();
    }
}
