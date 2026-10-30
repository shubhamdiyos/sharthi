package com.saathi.stock.domain;

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
 * A promise of quantity to one order line (§7.2). It is created at T1 or T3 depending on policy,
 * trued up once at weighing, and released or consumed at the transition that ends the line.
 */
@Entity
@Table(name = "stock_reservation")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class StockReservation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "product_id", length = 26, nullable = false)
    private String productId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_id", length = 26, nullable = false)
    private String orderId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "order_line_id", length = 26)
    private String orderLineId;

    @Column(name = "qty_reserved", nullable = false)
    private BigDecimal qtyReserved;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ReservationState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
