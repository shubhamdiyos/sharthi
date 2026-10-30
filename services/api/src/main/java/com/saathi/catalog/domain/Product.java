package com.saathi.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * One sellable item of one shop (§5.3).
 *
 * <p>Three independent switches live here and are never collapsed into a status: {@code available}
 * is the merchant's claim, {@code track_stock} decides whether a count is enforced, and
 * {@code active} decides whether the row exists for new orders at all (PRD §8.3 keeps history, so
 * deactivation is a flag, never a delete).
 *
 * <p>{@code reservedQty} is a stored scalar because §7.3's concurrency guard is one conditional
 * UPDATE against it. It is not derived on read: two customers must not both be able to see the last
 * packet, and a read-then-write cannot promise that.
 */
@Entity
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Product {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 26, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 26, nullable = false)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_id", length = 26, nullable = false)
    private String categoryId;

    /** Public, platform-unique: {@code GET /products/{code}} carries no city (§10.5.3). */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "image_key")
    private String imageKey;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "is_by_weight", nullable = false)
    private boolean byWeight;

    @Column(name = "weight_grams")
    private BigDecimal weightGrams;

    @Column(name = "pack_qty")
    private BigDecimal packQty;

    @Column(name = "track_stock", nullable = false)
    private boolean trackStock;

    @Column(name = "stock_qty", nullable = false)
    private BigDecimal stockQty;

    @Column(name = "reserved_qty", nullable = false)
    private BigDecimal reservedQty;

    @Column(name = "stock_unit", nullable = false)
    private String stockUnit;

    @Column(name = "allow_oversell", nullable = false)
    private boolean allowOversell;

    @Column(name = "max_qty_per_order", nullable = false)
    private int maxQtyPerOrder;

    @Column(name = "available", nullable = false)
    private boolean available;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** §7.1: sellable iff active, available, and — when counted — something left to promise. */
    public boolean isSellable() {
        if (!active || !available) {
            return false;
        }
        return !trackStock || allowOversell || stockQty.subtract(reservedQty).signum() > 0;
    }

    /** §7.1's derived projection, never stored: {@code IN}, {@code LOW} or {@code OUT}. */
    public String stockState() {
        if (!trackStock) {
            return "IN";
        }
        BigDecimal left = stockQty.subtract(reservedQty);
        if (left.signum() <= 0) {
            return "OUT";
        }
        return stockQty.compareTo(BigDecimal.TEN) <= 0 ? "LOW" : "IN";
    }
}
