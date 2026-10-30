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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What a delivery costs in one ward, in one fulfilment mode, from one moment onward (§5.2).
 *
 * <p>A live rate is never edited. A new row with a later {@code validFrom} replaces it, and the
 * unique key {@code (tenant_id, ward_id, fulfilment_mode, valid_from)} is what makes "two active
 * rules for the same ward-mode at the same instant" impossible. The reason is §9.7: an order's
 * fee has to be recomputable years later, and a rate that was overwritten in place cannot be
 * reproduced — which turns every money-identity test into a guess.
 */
@Entity
@Table(name = "ward_fee_rule")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class WardFeeRule {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    /** Part of the composite FK to {@code service_ward (tenant_id, id)}: a Siwan rule can never
     * name a Mashaun ward. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_id", length = 26, nullable = false)
    private String wardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_mode", nullable = false)
    private FulfilmentMode fulfilmentMode;

    @Column(name = "delivery_fee_paise", nullable = false)
    private long deliveryFeePaise;

    @Column(name = "handling_fee_paise", nullable = false)
    private long handlingFeePaise;

    @Builder.Default
    @Column(name = "min_order_paise", nullable = false)
    private long minOrderPaise = 0;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom = Instant.EPOCH;
}
