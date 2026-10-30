package com.saathi.order.domain;

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
 * A placed order (§5.5). The public identity is {@code code}, never {@code id} (§4.3-6).
 *
 * <p>Every money figure is copied from the accepted quote at placement and never recomputed from
 * the catalogue: the order is what the customer agreed to, and a later price edit must not rewrite
 * it (§8.4, D4). {@code status} changes only along §6.3, enforced by a database trigger as well as
 * by the service.
 */
@Entity
@Table(name = "\"order\"")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Order {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 26, nullable = false)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "customer_id", length = 26, nullable = false)
    private String customerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_id", length = 26, nullable = false)
    private String wardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "fulfilment_mode_snapshot", nullable = false)
    private String fulfilmentModeSnapshot;

    @Column(name = "fulfilment_locked_at", nullable = false)
    private Instant fulfilmentLockedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_channel", nullable = false)
    private CollectionChannel collectionChannel;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false)
    private SettlementStatus settlementStatus;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_id", length = 26, nullable = false)
    private String quoteId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "goods_paise", nullable = false)
    private long goodsPaise;

    @Column(name = "delivery_fee_paise", nullable = false)
    private long deliveryFeePaise;

    @Column(name = "handling_fee_paise", nullable = false)
    private long handlingFeePaise;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @Column(name = "commission_bp_snapshot", nullable = false)
    private int commissionBpSnapshot;

    @Column(name = "commission_paise", nullable = false)
    private long commissionPaise;

    @Column(name = "collected_paise", nullable = false)
    private long collectedPaise;

    @Column(name = "outstanding_paise", nullable = false)
    private long outstandingPaise;

    @Column(name = "delivery_note")
    private String deliveryNote;

    @Column(name = "address_snapshot", nullable = false)
    private String addressSnapshot;

    @Column(name = "contact_phone_cipher", nullable = false)
    private String contactPhoneCipher;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "address_changed_count", nullable = false)
    private int addressChangedCount;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "fail_reason")
    private String failReason;

    public boolean isTerminal() {
        return status == OrderStatus.DELIVERED
            || status == OrderStatus.REJECTED
            || status == OrderStatus.CANCELLED;
    }
}
