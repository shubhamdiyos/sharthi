package com.saathi.catalog.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.repository.ProductRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of {@code product.reserved_qty} and {@code stock_qty} (§7.3).
 *
 * <p>Every quantity change is a conditional single statement, never a read followed by a write.
 * {@code STOCK_CHANGED} is raised when the condition fails, which is the honest answer to "the
 * packet moved between the quote and the order" — the alternative is an oversell discovered at the
 * merchant's door.
 *
 * <p>The class also owns the row locks the quote and order paths take (§7.3, ascending id order), so
 * two carts touching the same two products cannot deadlock by locking them in opposite orders.
 */
@Service
@RequiredArgsConstructor
public class StockBook {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveOrFail(String tenantId, String productId, BigDecimal need) {
        int updated = jdbc.update("""
            UPDATE product
               SET reserved_qty = reserved_qty + ?
             WHERE id = ?
               AND tenant_id = ?
               AND active
               AND available
               AND track_stock
               AND (allow_oversell OR stock_qty >= reserved_qty + ?)
            """, need, productId, tenantId, need);
        if (updated == 0) {
            throw new ApiException(ErrorCode.STOCK_CHANGED,
                "One of the items in your cart just went out of stock.",
                Map.of("product_id", productId));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String tenantId, String productId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        int updated = jdbc.update("""
            UPDATE product
               SET reserved_qty = reserved_qty - ?
             WHERE id = ? AND tenant_id = ? AND reserved_qty >= ?
            """, qty, productId, tenantId, qty);
        if (updated == 0) {
            // A release that cannot be honoured is a corrupted reservation, not a client error;
            // refusing the whole transaction is the only outcome that keeps the count honest.
            throw new IllegalStateException("reservation release underflow for product " + productId);
        }
    }

    /** §7.2's weighing true-up: the only place a held quantity may change size. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void trueUp(String tenantId, String productId, BigDecimal from, BigDecimal to) {
        BigDecimal delta = to.subtract(from);
        if (delta.signum() > 0) {
            int updated = jdbc.update("""
                UPDATE product
                   SET reserved_qty = reserved_qty + ?
                 WHERE id = ? AND tenant_id = ?
                   AND (allow_oversell OR stock_qty >= reserved_qty + ?)
                """, delta, productId, tenantId, delta);
            if (updated == 0) {
                throw new ApiException(ErrorCode.STOCK_CHANGED,
                    "The weighed quantity is more than the shop has left.");
            }
        } else if (delta.signum() < 0) {
            release(tenantId, productId, delta.negate());
        }
    }

    /** Delivery consumes the held quantity: the goods left the shop. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String tenantId, String productId, BigDecimal qty) {
        int updated = jdbc.update("""
            UPDATE product
               SET stock_qty = stock_qty - ?, reserved_qty = reserved_qty - ?
             WHERE id = ? AND tenant_id = ? AND stock_qty >= ? AND reserved_qty >= ?
            """, qty, qty, productId, tenantId, qty, qty);
        if (updated == 0) {
            throw new IllegalStateException("reservation consume underflow for product " + productId);
        }
    }

    /** A merchant's counted adjustment: one explained delta, never a silent overwrite. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Product adjust(String tenantId, String productId, BigDecimal delta) {
        Product product = products.findByIdAndTenantId(productId, tenantId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Product not found."));
        if (!product.isTrackStock()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "This product does not track stock; turn counting on before adjusting it.");
        }
        int updated = jdbc.update("""
            UPDATE product
               SET stock_qty = stock_qty + ?
             WHERE id = ? AND tenant_id = ? AND stock_qty + ? >= 0
            """, delta, productId, tenantId, delta);
        if (updated == 0) {
            throw new ApiException(ErrorCode.NEGATIVE_STOCK, "Stock cannot go below zero.");
        }
        product.setStockQty(product.getStockQty().add(delta));
        return product;
    }

    /**
     * §7.3: lock the products a quote or order touches in ascending id order, so two concurrent
     * baskets cannot deadlock and the price read is the price written.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Product> lockForUpdate(String tenantId, List<String> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        List<String> ordered = productIds.stream().distinct().sorted().toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(ordered.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.addAll(ordered);
        List<String> lockedIds = jdbc.query(
            "SELECT id FROM product WHERE tenant_id = ? AND id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
            (rs, rowNum) -> rs.getString(1),
            args.toArray());
        return lockedIds.stream()
            .map(id -> products.findByIdAndTenantId(id, tenantId).orElseThrow())
            .toList();
    }
}
