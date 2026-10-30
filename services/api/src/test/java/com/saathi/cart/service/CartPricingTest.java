package com.saathi.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.saathi.catalog.domain.Product;
import com.saathi.merchant.domain.FulfilmentCapability;
import com.saathi.merchant.domain.Merchant;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.tenancy.domain.FulfilmentMode;
import java.math.BigDecimal;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line rules both paths share (§7.1, §8.4): a piece is a whole number, a weight carries at most
 * three decimals, the shop's cap wins, and an unstocked item is refused by name.
 */
class CartPricingTest {

    private static Product piece(int maxQtyPerOrder) {
        return Product.builder()
            .code("tata-salt-1kg")
            .name("Tata Salt")
            .byWeight(false)
            .maxQtyPerOrder(maxQtyPerOrder)
            .active(true)
            .available(true)
            .build();
    }

    private static Product weight() {
        return Product.builder()
            .code("chicken-curry-cut")
            .name("Chicken (curry cut)")
            .byWeight(true)
            .maxQtyPerOrder(5)
            .active(true)
            .available(true)
            .build();
    }

    private static Merchant shop(FulfilmentCapability capability, FulfilmentMode fallback) {
        return Merchant.builder()
            .fulfilmentCapability(capability)
            .defaultFulfilmentMode(fallback)
            .build();
    }

    @Test
    @DisplayName("a piece cannot be bought in halves, a weight can, and three decimals is the floor")
    void quantityShapeFollowsTheProductKind() {
        assertThatThrownBy(() -> CartPricing.requireQuantityShape(piece(10), new BigDecimal("2.5")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatCode(() -> CartPricing.requireQuantityShape(piece(10), new BigDecimal("3")))
            .doesNotThrowAnyException();
        assertThatCode(() -> CartPricing.requireQuantityShape(weight(), new BigDecimal("0.985")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> CartPricing.requireQuantityShape(weight(), new BigDecimal("0.9855")))
            .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("zero, the platform ceiling and the shop's own cap are each refused by their own code")
    void quantityLimits() {
        assertThatThrownBy(() -> CartPricing.requireQuantityShape(piece(10), BigDecimal.ZERO))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CartPricing.requireQuantityShape(piece(10), new BigDecimal("1000")))
            .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> CartPricing.requireQuantityShape(piece(4), new BigDecimal("5")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.MAX_QTY_EXCEEDED));
    }

    @Test
    @DisplayName("availability is checked against what is left, not only the on/off switch")
    void stockIsWhatRemains() {
        Product stocked = Product.builder()
            .code("tata-salt-1kg")
            .name("Tata Salt")
            .trackStock(true)
            .allowOversell(false)
            .stockQty(new BigDecimal("3"))
            .reservedQty(new BigDecimal("1"))
            .active(true)
            .available(true)
            .build();

        assertThatCode(() -> CartPricing.requireSellable(stocked, new BigDecimal("2")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> CartPricing.requireSellable(stocked, new BigDecimal("3")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.STOCK_CHANGED));
    }

    @Test
    @DisplayName("the shop's capability decides the mode, and a choice it cannot honour is refused")
    void modeResolution() {
        Merchant platformOnly = shop(FulfilmentCapability.PLATFORM_ONLY, FulfilmentMode.PLATFORM);
        Merchant selfDelivers = shop(FulfilmentCapability.MERCHANT_ONLY, FulfilmentMode.MERCHANT);
        Merchant both = shop(FulfilmentCapability.BOTH, FulfilmentMode.PLATFORM);

        assertThat(CartPricing.resolveMode(platformOnly, null, EnumSet.allOf(FulfilmentMode.class)))
            .isEqualTo(FulfilmentMode.PLATFORM);
        assertThat(CartPricing.resolveMode(both, "merchant", EnumSet.allOf(FulfilmentMode.class)))
            .isEqualTo(FulfilmentMode.MERCHANT);
        assertThatThrownBy(() -> CartPricing.resolveMode(platformOnly, "MERCHANT",
            EnumSet.allOf(FulfilmentMode.class)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code())
                .isEqualTo(ErrorCode.MODE_FORBIDS_TRANSITION));
        assertThatThrownBy(() -> CartPricing.resolveMode(selfDelivers, "PLATFORM",
            EnumSet.allOf(FulfilmentMode.class)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code())
                .isEqualTo(ErrorCode.MODE_FORBIDS_TRANSITION));
        assertThatThrownBy(() -> CartPricing.resolveMode(platformOnly, "TELEPORT",
            EnumSet.allOf(FulfilmentMode.class)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
