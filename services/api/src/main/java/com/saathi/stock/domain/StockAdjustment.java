package com.saathi.stock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** One explained change to a count (§5.3). A zero delta is refused by the database. */
@Entity
@Table(name = "stock_adjustment")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class StockAdjustment {

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

    @Column(name = "delta_qty", nullable = false)
    private BigDecimal deltaQty;

    @Column(name = "reason", nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_id", length = 26, nullable = false)
    private String actorId;

    @Column(name = "at", nullable = false)
    private Instant at;
}
