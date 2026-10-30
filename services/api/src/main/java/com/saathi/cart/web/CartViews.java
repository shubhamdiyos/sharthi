package com.saathi.cart.web;

import com.saathi.cart.domain.Cart;
import com.saathi.cart.domain.CartLine;
import com.saathi.catalog.domain.Product;
import com.saathi.merchant.domain.Merchant;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.tenancy.domain.ServiceWard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * §10.5.4's {@code CartView}.
 *
 * <p>The cart states line totals at the price the customer saw and a goods subtotal — never a
 * delivery total. §10.5.4 is explicit that {@code POST /quotes} is "the only way to get a total",
 * because the fee depends on the ward and the mode and a basket is not yet a decision about either.
 */
public final class CartViews {

    private CartViews() {
    }

    public record CartView(
        Basket cart,
        List<Line> lines,
        MoneyView subtotal,
        QuoteViews.QuoteView activeQuote) {
    }

    /** Named {@code Basket} so it cannot shadow the persisted {@code cart} entity in this file. */
    public record Basket(String id, Shop shop, Ward ward, Instant updatedAt) {
    }

    /** The shop the basket is locked to (§8.4), or null before the first line. */
    public record Shop(String code, String name) {
    }

    public record Ward(int wardNo, String name) {
    }

    public record Line(
        String id,
        String productCode,
        String name,
        String unitLabel,
        boolean byWeight,
        BigDecimal qty,
        MoneyView unitPriceAtAdd,
        MoneyView lineTotal,
        boolean available,
        String stockState,
        int maxQtyPerOrder,
        String imageKey) {
    }

    public static CartView empty(ServiceWard ward) {
        return new CartView(
            new Basket(null, null, wardOf(ward), null),
            List.of(),
            MoneyView.of(Money.zero()),
            null);
    }

    public static CartView of(Cart cart, Merchant merchant, ServiceWard ward, List<Product> products,
                              List<CartLine> lines, QuoteViews.QuoteView activeQuote) {
        Map<String, Product> byId = new HashMap<>();
        for (Product product : products) {
            byId.put(product.getId(), product);
        }
        boolean weighed = lines.stream()
            .anyMatch(line -> byId.containsKey(line.getProductId())
                && byId.get(line.getProductId()).isByWeight());
        long subtotal = 0;
        List<Line> rows = new ArrayList<>(lines.size());
        for (CartLine line : lines) {
            Product product = byId.get(line.getProductId());
            if (product == null) {
                continue;
            }
            long linePaise = Money.ofPaise(line.getUnitPricePaiseAtAdd())
                .multipliedBy(line.getQty()).paise();
            subtotal = Math.addExact(subtotal, linePaise);
            rows.add(new Line(
                line.getId(),
                product.getCode(),
                product.getName(),
                product.getUnitLabel(),
                product.isByWeight(),
                line.getQty(),
                MoneyView.of(Money.ofPaise(line.getUnitPricePaiseAtAdd()), product.isByWeight()),
                MoneyView.of(Money.ofPaise(linePaise), product.isByWeight()),
                product.isActive() && product.isAvailable(),
                product.stockState(),
                product.getMaxQtyPerOrder(),
                product.getImageKey()));
        }
        return new CartView(
            new Basket(
                cart.getId(),
                merchant == null ? null : new Shop(merchant.getCode(), merchant.getTradeName()),
                wardOf(ward),
                cart.getUpdatedAt()),
            List.copyOf(rows),
            MoneyView.of(Money.ofPaise(subtotal), weighed),
            activeQuote);
    }

    private static Ward wardOf(ServiceWard ward) {
        return new Ward(ward.getWardNo(), ward.getName());
    }
}
