package com.saathi.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Who may do what, where (§5.1, §11.3). A grant is {@code (principal, role, scope)} and the scope
 * is the triple {@code tenant / merchant / ward} — all nullable, all checked in the database.
 *
 * <p>Two things this row shape buys and no code could:
 * <ul>
 *   <li>Revoking one grant ends that principal's data access at the <em>next request</em>
 *       (§12.5), because the scope is resolved from here on every call — not cached in the cookie.</li>
 *   <li>A tenantless grant is legal for a platform role and for nothing else, and the
 *       {@code user_role_grant_platform_shape} trigger (SQLSTATE {@code SA002}) is what says so —
 *       a CHECK cannot read the {@code role} table to ask whether the role is a platform role.</li>
 * </ul>
 *
 * <p>{@code reason} is NOT NULL and 3-500 characters: §13.2's position is that a permission nobody
 * can explain later is an audit failure, so the row cannot be written without the sentence.
 */
@Entity
@Table(name = "user_role_grant")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class UserRoleGrant {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_account_id", length = 26, nullable = false)
    private String userAccountId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "role_id", length = 26, nullable = false)
    private String roleId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26)
    private String tenantId;

    /** Null until the merchant module exists (§5.1); the composite FK arrives with it. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 26)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_id", length = 26)
    private String wardId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "granted_by", length = 26, nullable = false)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "revoked_by", length = 26)
    private String revokedBy;

    /** Null means live. There is no {@code active} boolean to fall out of step with this. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "reason", nullable = false)
    private String reason;

    public boolean isLive() {
        return revokedAt == null;
    }
}
