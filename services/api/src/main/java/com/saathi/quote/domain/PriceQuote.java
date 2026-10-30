package com.saathi.quote.domain;

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
 * The artifact a customer consented to (§8.1), never a cache of a price.
 *
 * <p>Every amount is the exact paise value at issue, and {@code quoteHash} is a digest of those
 * amounts. That is what makes "the price changed between quote and placement" a comparison of two
 * strings rather than an argument about when a row was read.
 */
@Entity
@Table(name = "price_quote")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class PriceQuote {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private QuoteScope scope;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cart_id", length = 26)
    private String cartId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", length = 26)
    private String orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_hash", nullable = false, length = 64)
    private String quoteHash;

    @Column(name = "goods_paise", nullable = false)
    private long goodsPaise;

    @Column(name = "delivery_fee_paise", nullable = false)
    private long deliveryFeePaise;

    @Column(name = "handling_fee_paise", nullable = false)
    private long handlingFeePaise;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @Column(name = "commission_paise", nullable = false)
    private long commissionPaise;

    @Column(name = "consent_requirements_json", nullable = false)
    private String consentRequirementsJson;

    @Column(name = "fulfilment_options_json", nullable = false)
    private String fulfilmentOptionsJson;

    @Column(name = "fulfilment_mode_snapshot", nullable = false)
    private String fulfilmentModeSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private QuoteState state;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "accepted_by", length = 26)
    private String acceptedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public boolean isActive() {
        return state == QuoteState.ACTIVE;
    }

    /** §8.2 / §6.5: expiry is a comparison, never a scheduled transition. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
