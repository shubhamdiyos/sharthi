package com.saathi.shared.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.tenant.PrincipalKind;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §13.2's ⚑ actions. The list is enforced here rather than remembered at each call site,
 * because a reason code that only some callers supply is not a control — and the reviewer
 * reading an exception queue cannot tell "no reason given" from "reason not required".
 */
class AuditEntryTest {

    private static AuditEntry.AuditEntryBuilder entry(String action) {
        return AuditEntry.builder()
            .tenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
            .actorType(PrincipalKind.OPERATOR)
            .actorId("01ARZ3NDEKTSV4RRFFQ69G5FAW")
            .action(action)
            .entity("order")
            .entityId("01ARZ3NDEKTSV4RRFFQ69G5FAX")
            .occurredAt(Instant.parse("2026-10-30T09:15:42.710Z"));
    }

    @Test
    void everyFlaggedActionDemandsAReason() {
        for (String action : AuditEntry.REASON_CODE_REQUIRED) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                entry(action).build()::requireReasonCode, action + " is ⚑ in §13.2");
            assertTrue(e.getMessage().contains(action), e.getMessage());
            assertTrue(e.getMessage().contains("§13.2"), "the message names the rule it enforces");
            assertDoesNotThrow(entry(action).reasonCode("CUSTOMER_REFUSED_UPI").build()::requireReasonCode);
        }
    }

    @Test
    void anUnflaggedActionDoesNotInventAReasonRequirement() {
        assertDoesNotThrow(entry("ORDER_VIEWED").build()::requireReasonCode);
    }

    @Test
    void aBlankReasonIsNotAReason() {
        for (String blank : new String[] {"", "   ", "\t"}) {
            assertThrows(IllegalArgumentException.class,
                entry("REFUND_RECORDED").reasonCode(blank).build()::requireReasonCode,
                "[" + blank + "]");
        }
    }

    @Test
    void theFlaggedSetIsTheOneFromSectionThirteenPointTwo() {
        assertEquals(Set.of(
            "GRANT_ADDED", "GRANT_REVOKED", "USER_DEACTIVATED", "USER_FORCE_LOGOUT", "PHONE_CHANGED",
            "JOB_DECLINED", "ASSIGN_OVERRIDE", "RIDER_LIMIT_CHANGED", "PAYOUT_TO_MERCHANT",
            "SETTLEMENT_BATCH_APPROVED", "REFUND_RECORDED", "SHORTFALL_WRITTEN_OFF", "RATE_RULE_WRITE",
            "PII_REVEALED", "STATE_TRANSITION", "WEIGHED"), AuditEntry.REASON_CODE_REQUIRED);
    }

    @Test
    void theBuilderCarriesTheEvidenceFields() {
        AuditEntry entry = entry("STATE_TRANSITION")
            .reasonCode("OPERATOR_OVERRIDE")
            .beforeJson("{\"status\":\"ACCEPTED\"}")
            .afterJson("{\"status\":\"PREPARING\"}")
            .requestId("01ARZ3NDEKTSV4RRFFQ69G5FAY")
            .ipHash("0".repeat(64))
            .build();

        assertEquals("STATE_TRANSITION", entry.action());
        assertEquals("OPERATOR_OVERRIDE", entry.reasonCode());
        assertEquals(PrincipalKind.OPERATOR, entry.actorType());
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", entry.tenantId());
        assertEquals(Instant.parse("2026-10-30T09:15:42.710Z"), entry.occurredAt());
        assertEquals("{\"status\":\"ACCEPTED\"}", entry.beforeJson());
        // The id is minted by the recorder, not by the caller, and a state that never existed
        // has no snapshot — absent, not empty.
        assertNull(entry.id());
    }

    @Test
    void aSystemActionCarriesNoActor() {
        AuditEntry system = entry("STATE_TRANSITION")
            .actorType(PrincipalKind.SYSTEM)
            .actorId(null)
            .reasonCode("NIGHTLY_ASSERTION")
            .build();
        assertDoesNotThrow(system::requireReasonCode);
        assertEquals(PrincipalKind.SYSTEM, system.actorType());
        assertNull(system.actorId());
    }
}
