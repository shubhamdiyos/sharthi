package com.saathi.cart.domain;

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

/**
 * One product in a basket (§5.4).
 *
 * <p>{@code unitPricePaiseAtAdd} is deliberately not refreshed when the catalogue changes: it is
 * the price the customer saw when they added the item, and the difference between it and today's
 * price is exactly what §8.2's consent rule is about. Adding the same product again only raises
 * {@code qty}, so the one-row-per-product constraint and the price that was shown both hold.
 */
@Entity
@Table(name = "cart_line")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class CartLine {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cart_id", length = 26, nullable = false)
    private String cartId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "product_id", length = 26, nullable = false)
    private String productId;

    @Column(name = "qty", nullable = false)
    private BigDecimal qty;

    @Column(name = "unit_price_paise_at_add", nullable = false)
    private long unitPricePaiseAtAdd;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
