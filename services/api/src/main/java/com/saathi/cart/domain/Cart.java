package com.saathi.cart.domain;

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
 * One customer's basket in one city (§5.4).
 *
 * <p>The merchant is set by the first line and cleared when the last one goes: an empty cart has
 * no shop, and a cart with lines cannot pretend it does not (§8.4). Changing the merchant of a
 * non-empty cart is not an operation this entity allows, which is why the rule lives in the
 * service and the column stays nullable.
 */
@Entity
@Table(name = "cart")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Cart {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "customer_id", length = 26, nullable = false)
    private String customerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 26)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ward_id", length = 26, nullable = false)
    private String wardId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
