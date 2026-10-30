package com.saathi.tenancy.domain;

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
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * A city (§5.2). This is the row every scoped query in the platform ultimately filters on, and
 * the row whose {@code status} decides whether a customer is allowed to reach a cart at all.
 *
 * <p>Money columns are named {@code *Paise} because Hibernate's naming strategy turns the field
 * into the snake_case column the migration created; a field called {@code softCashLimit} would be
 * looked for as {@code soft_cash_limit} and validation would fail with "missing column", which is
 * the loudest possible way to say "the entity and the migration disagree".
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Tenant {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    /** Public, stable and short — {@code SIWAN}. This is the identifier the API accepts (§4.3-6). */
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status;

    @Builder.Default
    @Column(name = "default_commission_bp", nullable = false)
    private int defaultCommissionBp = 0;

    @Column(name = "soft_cash_limit_paise", nullable = false)
    private long softCashLimitPaise;

    @Column(name = "hard_cash_limit_paise", nullable = false)
    private long hardCashLimitPaise;

    /** OD-16's answer lives here per city; §5.2's CHECK closes the list. */
    @Column(name = "stock_policy", nullable = false)
    private String stockPolicy;

    @Column(name = "weight_tolerance_bp")
    private Integer weightToleranceBp;

    @Column(name = "cart_ttl_minutes", nullable = false)
    private int cartTtlMinutes;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
