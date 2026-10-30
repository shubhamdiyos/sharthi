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
 * A person (§5.1). Global, not per tenant: the same customer can order in Siwan and later in
 * Mashaun without duplicating their identity, and an operator who also owns a shop is one account
 * with two grants — otherwise {@code audit_event.actor_id} stops naming a human (§12.1).
 *
 * <p>{@code kind} is a guard, not a label. {@code user_account_credential_shape} enforces in the
 * database what §12.1 says in prose: a customer or rider has a phone and can never hold a
 * password, and a staff principal has an email and always carries a recovery phone.
 */
@Entity
@Table(name = "user_account")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class UserAccount {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    /**
     * Reuses the kernel's {@link PrincipalKind} deliberately: the column
     * accepts the same five values, and a sixth kind — {@code SYSTEM} — is refused by
     * {@code user_account_kind} because a job is not a person and must not be able to appear in
     * the operator's user list.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private PrincipalKind kind;

    /** E.164 as {@link com.saathi.shared.contact.PhoneNormaliser} produces it. The factor (§12.1). */
    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AccountState state = AccountState.ACTIVE;

    /**
     * Argon2id's encoded form, and the only field on this entity that may never reach a response,
     * a log line or a fixture (§12.2, §14). Null for customers and riders by CHECK, not by chance.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    @Builder.Default
    @Column(name = "failed_auth_count", nullable = false)
    private int failedAuthCount = 0;

    /** Set with the lock, and the reason {@code state = LOCKED} is temporary (§12.8). */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "terms_version_accepted")
    private String termsVersionAccepted;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    public boolean isStaff() {
        return kind == PrincipalKind.MERCHANT_STAFF
            || kind == PrincipalKind.OPERATOR
            || kind == PrincipalKind.SUPER_ADMIN;
    }
}
