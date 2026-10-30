package com.saathi.cart.service;

import com.saathi.cart.domain.Cart;
import com.saathi.cart.domain.CartLine;
import com.saathi.cart.repository.CartLineRepository;
import com.saathi.catalog.domain.Product;
import com.saathi.catalog.service.CatalogShelf;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.quote.domain.QuoteScope;
import com.saathi.quote.service.QuoteInput;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.shared.money.Rate;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.domain.WardFeeRule;
import com.saathi.tenancy.service.ServiceabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a cart is worth right now (§8.1), written once so the quote the customer is shown and the
 * quote {@code POST /orders} re-validates cannot disagree.
 *
 * <p>The class takes the product locks (§7.3) before it reads a price: a total computed from an
 * unlocked read is a total another placement can already have invalidated. It also owns the rules
 * a cart line must satisfy before it can be quoted — sellability, the shop's per-order cap, stock
 * and the ward's minimum — because those are the same rules in both paths.
 */
@Service
@RequiredArgsConstructor
public class CartPricing {

    private static final BigDecimal MAX_QTY = new BigDecimal("999");

    private final CartLineRepository lines;
    private final CatalogShelf shelf;
    private final MerchantDirectory directory;
    private final ServiceabilityService serviceability;

    /**
     * A priced cart plus everything the caller needs to compare it with an earlier quote.
     * {@code input} is what {@code quote/} persists; the loose amounts are for the comparison
     * {@code POST /orders} makes before it decides to re-issue or refuse.
     */
    public record Priced(
        Cart cart,
        Merchant merchant,
        ServiceWard ward,
        String mode,
        QuoteInput input,
        List<Product> products,
        long goodsPaise,
        long deliveryFeePaise,
        long handlingFeePaise,
        long totalPaise) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Priced price(Tenant tenant, Cart cart, ServiceWard ward, String requestedMode,
                        Instant issuedAt) {
        List<CartLine> cartLines = lines.findByCartIdOrderByAddedAtAsc(cart.getId());
        if (cartLines.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Your cart is empty.");
        }
        if (cart.getMerchantId() == null) {
            throw new IllegalStateException("a non-empty cart must have a merchant");
        }
        Merchant merchant = directory.shopInTenant(cart.getTenantId(), cart.getMerchantId());
        if (!merchant.isVisible()) {
            throw new ApiException(ErrorCode.MERCHANT_UNAVAILABLE,
                "That shop is not taking orders right now.");
        }

        List<String> productIds = cartLines.stream().map(CartLine::getProductId).toList();
        Map<String, Product> products = new HashMap<>();
        for (Product product : shelf.lockForQuote(cart.getTenantId(), productIds)) {
            products.put(product.getId(), product);
        }

        Map<FulfilmentMode, WardFeeRule> rules = new EnumMap<>(FulfilmentMode.class);
        for (WardFeeRule rule : serviceability.effectiveFees(tenant, ward)) {
            rules.put(rule.getFulfilmentMode(), rule);
        }
        FulfilmentMode mode = resolveMode(merchant, requestedMode, rules.keySet());
        WardFeeRule fee = rules.get(mode);
        if (fee == null) {
            throw new ApiException(ErrorCode.WARD_NOT_SERVED,
                "That shop cannot deliver this way to your area.");
        }

        long goodsPaise = 0;
        List<QuoteInput.Line> quoteLines = new ArrayList<>(cartLines.size());
        List<QuoteInput.Consent> consents = new ArrayList<>();
        for (CartLine line : cartLines) {
            Product product = products.get(line.getProductId());
            if (product == null) {
                throw new ApiException(ErrorCode.STOCK_CHANGED,
                    "One of the items in your cart is no longer available.",
                    Map.of("product_id", line.getProductId()));
            }
            requireSellable(product, line.getQty());
            long linePaise = Money.ofPaise(product.getPricePaise())
                .multipliedBy(line.getQty()).paise();
            goodsPaise = Math.addExact(goodsPaise, linePaise);

            long delta = product.getPricePaise() - line.getUnitPricePaiseAtAdd();
            boolean consent = delta > 0;
            if (consent) {
                consents.add(new QuoteInput.Consent("PRICE_INCREASED", product.getId(),
                    line.getUnitPricePaiseAtAdd(), product.getPricePaise()));
            }
            quoteLines.add(new QuoteInput.Line(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getUnitLabel(),
                line.getQty(),
                null,
                product.getPricePaise(),
                delta,
                consent));
        }

        if (goodsPaise < fee.getMinOrderPaise()) {
            throw new ApiException(ErrorCode.MIN_ORDER_NOT_MET,
                "This shop needs a larger order.",
                Map.of("min_order", MoneyView.of(Money.ofPaise(fee.getMinOrderPaise())),
                    "goods", MoneyView.of(Money.ofPaise(goodsPaise))));
        }

        long totalPaise = Math.addExact(goodsPaise,
            Math.addExact(fee.getDeliveryFeePaise(), fee.getHandlingFeePaise()));
        long commissionPaise = Rate.ofBasisPoints(tenant.getDefaultCommissionBp())
            .applyTo(Money.ofPaise(goodsPaise)).paise();

        QuoteInput input = new QuoteInput(
            cart.getTenantId(),
            cart.getId(),
            null,
            QuoteScope.CART,
            mode.name(),
            fee.getDeliveryFeePaise(),
            fee.getHandlingFeePaise(),
            commissionPaise,
            tenant.getCartTtlMinutes(),
            issuedAt,
            List.copyOf(quoteLines),
            List.copyOf(consents),
            options(merchant, rules, goodsPaise));
        return new Priced(cart, merchant, ward, mode.name(), input,
            List.copyOf(products.values()), goodsPaise,
            fee.getDeliveryFeePaise(), fee.getHandlingFeePaise(), totalPaise);
    }

    /**
     * §6.2's selection: the capability decides what is possible, {@code customer_choice_allowed}
     * decides whether the customer picks, and the shop's default decides the rest.
     */
    static FulfilmentMode resolveMode(Merchant merchant, String requested,
                                      java.util.Set<FulfilmentMode> offered) {
        FulfilmentMode wanted = parseMode(requested);
        boolean canOfferPlatform = merchant.platformDelivers();
        boolean canOfferMerchant = merchant.deliversItself();
        if (!canOfferPlatform && !canOfferMerchant) {
            throw new IllegalStateException("a shop must support at least one fulfilment mode");
        }
        if (wanted != null && !offered.contains(wanted)) {
            throw new ApiException(ErrorCode.MODE_FORBIDS_TRANSITION,
                "That shop cannot deliver this way to your area.");
        }
        if (wanted != null) {
            if (wanted == FulfilmentMode.PLATFORM && !canOfferPlatform) {
                throw new ApiException(ErrorCode.MODE_FORBIDS_TRANSITION,
                    "That shop delivers its own orders.");
            }
            if (wanted == FulfilmentMode.MERCHANT && !canOfferMerchant) {
                throw new ApiException(ErrorCode.MODE_FORBIDS_TRANSITION,
                    "That shop uses platform delivery.");
            }
            return wanted;
        }
        return merchant.getDefaultFulfilmentMode();
    }

    private static FulfilmentMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FulfilmentMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "fulfilment_mode must be PLATFORM or MERCHANT.");
        }
    }

    /**
     * Both modes' totals freeze at issue (§5.2), so a fee edit after the quote was shown cannot
     * change the choice the customer was offered.
     */
    private static List<QuoteInput.Option> options(Merchant merchant,
                                                   Map<FulfilmentMode, WardFeeRule> rules,
                                                   long goodsPaise) {
        List<QuoteInput.Option> options = new ArrayList<>(2);
        for (Map.Entry<FulfilmentMode, WardFeeRule> entry : rules.entrySet()) {
            FulfilmentMode mode = entry.getKey();
            boolean offered = mode == FulfilmentMode.PLATFORM
                ? merchant.platformDelivers() : merchant.deliversItself();
            if (!offered) {
                continue;
            }
            WardFeeRule rule = entry.getValue();
            options.add(new QuoteInput.Option(mode.name(),
                Math.addExact(goodsPaise,
                    Math.addExact(rule.getDeliveryFeePaise(), rule.getHandlingFeePaise())),
                null));
        }
        return options;
    }

    /** The rules a single line must pass, shared by the add/patch endpoints and the quote. */
    public static void requireSellable(Product product, BigDecimal qty) {
        requireQuantityShape(product, qty);
        if (!product.isActive() || !product.isAvailable()) {
            throw new ApiException(ErrorCode.STOCK_CHANGED,
                "One of the items in your cart is no longer available.",
                Map.of("product_code", product.getCode()));
        }
        if (product.isTrackStock() && !product.isAllowOversell()) {
            BigDecimal left = product.getStockQty().subtract(product.getReservedQty());
            if (qty.compareTo(left) > 0) {
                throw new ApiException(ErrorCode.STOCK_CHANGED,
                    "Only " + left.stripTrailingZeros().toPlainString()
                        + " left of " + product.getName() + ".",
                    Map.of("product_code", product.getCode(),
                        "available_qty", left.stripTrailingZeros().toPlainString()));
            }
        }
    }

    /** Quantity shape without a stock read, for an add that is about to combine with a line. */
    public static void requireQuantityShape(Product product, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw fieldError("qty", "must be more than zero");
        }
        BigDecimal plain = qty.stripTrailingZeros();
        if (plain.scale() > 3) {
            throw fieldError("qty", "has more than three decimals");
        }
        if (!product.isByWeight() && plain.scale() > 0) {
            throw fieldError("qty", "must be a whole number for this item");
        }
        if (qty.compareTo(MAX_QTY) > 0) {
            throw fieldError("qty", "must be at most " + MAX_QTY.toPlainString());
        }
        if (product.getMaxQtyPerOrder() > 0
            && qty.compareTo(BigDecimal.valueOf(product.getMaxQtyPerOrder())) > 0) {
            throw new ApiException(ErrorCode.MAX_QTY_EXCEEDED,
                "This shop allows at most " + product.getMaxQtyPerOrder()
                    + " of " + product.getName() + " per order.",
                Map.of("product_code", product.getCode(),
                    "max_qty_per_order", product.getMaxQtyPerOrder()));
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }
}
