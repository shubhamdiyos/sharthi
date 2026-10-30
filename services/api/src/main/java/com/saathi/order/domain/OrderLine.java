package com.saathi.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of a placed order, copied from the accepted quote's snapshot (§5.5).
 *
 * <p>{@code qtyDelivered} is separate from {@code qtyWeighed} on purpose: what the customer paid
 * for and what actually arrived are two facts, and a delivery failure or a correction must not
 * erase the first one (§7.5).
 */
@Entity
@Table(name = "order_line")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class OrderLine {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", length = 26, nullable = false)
    private String orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "product_id", length = 26, nullable = false)
    private String productId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "name_snapshot", nullable = false)
    private String nameSnapshot;

    @Column(name = "unit_label_snapshot", nullable = false)
    private String unitLabelSnapshot;

    @Column(name = "qty_ordered", nullable = false)
    private BigDecimal qtyOrdered;

    @Column(name = "qty_weighed")
    private BigDecimal qtyWeighed;

    @Column(name = "qty_delivered")
    private BigDecimal qtyDelivered;

    @Column(name = "unit_price_paise", nullable = false)
    private long unitPricePaise;

    @Column(name = "line_paise", nullable = false)
    private long linePaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false)
    private PriceSource priceSource;

    @Column(name = "consented_at")
    private Instant consentedAt;
}
