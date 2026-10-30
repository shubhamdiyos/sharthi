package com.saathi.identity.domain;

import com.saathi.shared.tenant.PrincipalKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One logged-in device (§12.4, §12.5).
 *
 * <p><b>The row decides access, not the cookie.</b> The cookie carries this row's id and an HMAC
 * of it; every request re-reads the row and re-checks {@code revoked_at}, {@code idle_expires_at}
 * and {@code absolute_expires_at}. That is what makes §12.5's revocations immediate — a force
 * logout ends access on the next request without a token list, a cache to invalidate, or a sweeper
 * to run (§6.5 removed timers from the product, and a session reaper would be one of them).
 *
 * <p>The three {@code *_scope} columns are bound at creation from the resolved grant set. A city
 * operator's session cannot become another tenant's session by changing a request parameter
 * (§10.1), which is the whole of §11.1's scope argument expressed as one row.
 */
@Entity
@Table(name = "user_session")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class UserSession {

    /**
     * Also the credential's first half. A ULID, so a session sorts by when it was created and
     * {@code GET /auth/sessions} can show a customer their devices in the order they remember.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_account_id", length = 26, nullable = false)
    private String userAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_kind", nullable = false)
    private PrincipalKind principalKind;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_scope", length = 26)
    private String tenantScope;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_scope", length = 26)
    private String merchantScope;

    /** The storefront's city is resolved from here (§10.1), never from a request body. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_scope", length = 26)
    private String wardScope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Null for a principal with no idle timeout. Slid forward on each request that uses it. */
    @Column(name = "idle_expires_at")
    private Instant idleExpiresAt;

    /**
     * NOT NULL in V2 while §12.4 gives a customer "none". The value that means that is far enough
     * out that revocation is the only way the session ends — see {@code SessionPolicy}.
     */
    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Required with {@code revokedAt} by {@code session_revocation_reason} (§12.5). */
    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "device_label")
    private String deviceLabel;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_agent_hash", length = 64)
    private String userAgentHash;

    /** Expired or revoked, as of {@code at}. Expiry is evaluated on read, which is the only rule
     * that keeps §12.4's timeouts honest without a background job. */
    public boolean isUsableAt(Instant at) {
        if (revokedAt != null) {
            return false;
        }
        if (!at.isBefore(absoluteExpiresAt)) {
            return false;
        }
        return idleExpiresAt == null || at.isBefore(idleExpiresAt);
    }
}
