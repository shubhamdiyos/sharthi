package com.saathi.catalog.web;

import com.saathi.catalog.domain.Product;
import com.saathi.merchant.web.ShopViews;
import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.shared.paging.Page;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The catalogue's shapes: three views of one product, because three readers are allowed to see
 * three different things.
 *
 * <p>A storefront summary carries no {@code stock_qty} (§10.5.3): "3 left" is an invitation to
 * race, and the number is reserved against at placement anyway. The merchant's own view carries
 * the counts because maintaining them is the screen's job. The public detail carries the consent
 * notice because a by-weight price is an estimate and §7.5 makes saying so part of the contract.
 */
public final class CatalogViews {

    /**
     * §7.5's notice, in the words the checkout screen uses. It is a constant and not merchant
     * text: the rule is the platform's, and a shop cannot opt out of it by editing a field.
     */
    public static final String WEIGHT_CONSENT_NOTICE =
        "This item is sold by weight. The price shown is an estimate; you will be asked to "
            + "approve the final amount before it is delivered.";

    private CatalogViews() {
    }

    /** §10.5.3's {@code ProductSummary}: enough for a tile. */
    public record ProductSummary(
        String code,
        String name,
        String unitLabel,
        MoneyView price,
        boolean isByWeight,
        boolean available,
        String imageKey) {

        public static ProductSummary of(Product product) {
            return new ProductSummary(product.getCode(), product.getName(), product.getUnitLabel(),
                CatalogViews.price(product), product.isByWeight(), product.isAvailable(),
                product.getImageKey());
        }
    }

    /** §10.5.3's {@code ProductDetail}: the shop it belongs to and the consent notice. */
    public record ProductDetail(
        String code,
        String shopCode,
        String shopName,
        String categorySlug,
        String categoryName,
        String name,
        String description,
        String unitLabel,
        MoneyView price,
        boolean isByWeight,
        BigDecimal weightGrams,
        BigDecimal packQty,
        boolean available,
        int maxQtyPerOrder,
        String imageKey,
        String consentNotice) {
    }

    /** §10.5.6's {@code ProductView}: the merchant sees counts and the row's version. */
    public record ProductView(
        String code,
        String name,
        String description,
        String categorySlug,
        String categoryName,
        String unitLabel,
        MoneyView price,
        boolean isByWeight,
        BigDecimal weightGrams,
        BigDecimal packQty,
        boolean trackStock,
        BigDecimal stockQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        String stockUnit,
        boolean allowOversell,
        int maxQtyPerOrder,
        boolean available,
        boolean active,
        String stockState,
        String imageKey,
        long version,
        Instant updatedAt) {
    }

    /** One row of the stock screen: the same counts, narrowed to what that screen asks. */
    public record StockView(
        String code,
        String name,
        BigDecimal stockQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        String stockUnit,
        String stockState,
        boolean trackStock,
        boolean available,
        long version) {

        public static StockView of(Product product) {
            return new StockView(product.getCode(), product.getName(), product.getStockQty(),
                product.getReservedQty(), CatalogViews.availableQty(product), product.getStockUnit(),
                product.stockState(), product.isTrackStock(), product.isAvailable(),
                product.getVersion());
        }
    }

    /** §10.5.3's {@code GET /search}: one page, two sections, matching the drawn screen. */
    public record SearchView(List<ShopViews.Summary> shops, Page<ProductSummary> products) {

        public SearchView {
            shops = List.copyOf(shops);
        }
    }

    public static BigDecimal availableQty(Product product) {
        return product.isTrackStock()
            ? product.getStockQty().subtract(product.getReservedQty())
            : null;
    }

    /** A by-weight price is an estimate and says so in {@code approx} (§10.4, §7.5). */
    public static MoneyView price(Product product) {
        return MoneyView.of(Money.ofPaise(product.getPricePaise()), product.isByWeight());
    }
}
