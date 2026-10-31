package com.saathi.order.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.service.CatalogShelf;
import com.saathi.cart.service.CartPricing;
import com.saathi.order.domain.OrderLine;
import com.saathi.stock.domain.StockPolicy;
import com.saathi.stock.service.ReservationService;
import com.saathi.tenancy.domain.Tenant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * §7.2's holds, from an order's lines rather than a cart's.
 *
 * <p>Two moments commit stock and they are the same loop with a different policy name: T1 for a city
 * that reserves on placement, T3 for one that reserves on acceptance ({@code FLAG_ONLY} holds nothing
 * at either). Reading the policy here rather than at the call site is the point — the action declares
 * when it commits goods, and the configuration decides whether this is its turn.
 *
 * <p>{@link #lock} goes through {@code catalog/}, which takes the product rows in ascending id order
 * (§7.3). Two writes touching the same lines in opposite orders is a deadlock pair, and a deadlock
 * is a rolled-back order whose customer saw nothing but an error.
 */
@Service
@RequiredArgsConstructor
public class OrderStock {

    private final CatalogShelf shelf;
    private final ReservationService reservations;

    /**
     * Hold this order's lines, if {@code when} is the point the city and the shop agreed on.
     *
     * <p>A by-weight line is held at the estimate plus the tolerance, not at the estimate: the shop
     * has to be able to put a slightly bigger bag in the box without the platform discovering at
     * weighing (§7.2) that the extra grams were already promised to somebody else.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdFor(StockPolicy when, Tenant tenant, String merchantId, List<OrderLine> lines) {
        if (reservations.effectivePolicy(tenant.getStockPolicy(), merchantId) != when) {
            return;
        }
        Map<String, Product> products = lock(tenant.getId(), lines);
        int tolerance = WeightTolerance.basisPoints(tenant);
        for (OrderLine line : lines) {
            Product product = products.get(line.getProductId());
            if (!product.isTrackStock()) {
                continue;
            }
            BigDecimal charged = line.getQtyWeighed() == null
                ? line.getQtyOrdered() : line.getQtyWeighed();
            BigDecimal qty = product.isByWeight()
                ? WeightTolerance.ceilingFor(charged, tolerance) : charged;
            reservations.hold(tenant.getId(), line.getOrderId(), line.getId(),
                line.getProductId(), qty);
        }
    }

    /**
     * §7.4-3: the shop's acceptance is the third revalidation point, so the goods the order was
     * quoted for must still be sellable. Charged against the quoted quantity, never the live
     * catalogue's — the customer agreed to two, and the shop is being asked whether it has two.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertStillSellable(String tenantId, List<OrderLine> lines) {
        Map<String, Product> products = lock(tenantId, lines);
        for (OrderLine line : lines) {
            CartPricing.requireSellable(products.get(line.getProductId()), line.getQtyOrdered());
        }
    }

    /** The order's products, locked, by id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Product> lock(String tenantId, List<OrderLine> lines) {
        return index(shelf.lockForQuote(tenantId, productIds(lines)));
    }

    /** The order's products without a lock, for a caller that only reads a flag off them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Product> byId(String tenantId, List<OrderLine> lines) {
        return index(shelf.byIds(tenantId, productIds(lines)));
    }

    private static Map<String, Product> index(List<Product> rows) {
        Map<String, Product> byId = new HashMap<>();
        for (Product product : rows) {
            byId.put(product.getId(), product);
        }
        return byId;
    }

    private static List<String> productIds(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::getProductId).toList();
    }
}
