package com.saathi.shared.audit;

import com.saathi.shared.tenant.PrincipalKind;
import java.time.Instant;
import java.util.Set;
import lombok.Builder;

/**
 * One row of {@code audit_event}. Recorded inside the same transaction as the mutation it
 * describes — §13.1's rule is that a write either commits with its audit row or does not
 * commit, which an outbox or a deferred job cannot satisfy.
 *
 * <p>Thirteen fields, most of them optional, so the call sites use the builder: a positional
 * constructor here is a row whose {@code reasonCode} is silently in {@code requestId}.
 */
@Builder
public record AuditEntry(
    String id,
    String tenantId,
    PrincipalKind actorType,
    String actorId,
    String action,
    String entity,
    String entityId,
    String beforeJson,
    String afterJson,
    String reasonCode,
    String requestId,
    String ipHash,
    Instant occurredAt) {

    /**
     * Actions whose reason code is mandatory (§13.2, the ⚑ marks). Enforced here rather than
     * in each module, because a reason code that only some callers remember is not a control.
     */
    public static final Set<String> REASON_CODE_REQUIRED = Set.of(
        "GRANT_ADDED",
        "GRANT_REVOKED",
        "USER_DEACTIVATED",
        "USER_FORCE_LOGOUT",
        "PHONE_CHANGED",
        "JOB_DECLINED",
        "ASSIGN_OVERRIDE",
        "RIDER_LIMIT_CHANGED",
        "PAYOUT_TO_MERCHANT",
        "SETTLEMENT_BATCH_APPROVED",
        "REFUND_RECORDED",
        "SHORTFALL_WRITTEN_OFF",
        "RATE_RULE_WRITE",
        "PII_REVEALED",
        "STATE_TRANSITION",
        "WEIGHED");

    public void requireReasonCode() {
        if (REASON_CODE_REQUIRED.contains(action) && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("audit action " + action + " requires a reason code (§13.2)");
        }
    }
}
