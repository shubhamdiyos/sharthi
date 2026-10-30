package com.saathi.merchant.web;

import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.merchant.domain.Merchant;
import com.saathi.tenancy.domain.FulfilmentMode;
import java.util.List;

/**
 * The storefront's shop shapes (§10.5.3) and the operator's richer view (§10.5.8).
 *
 * <p>A summary carries no phone number and no hidden fields: it is rendered for a guest who has not
 * chosen a ward yet, and PRD §10's data rules apply before sign-in as much as after it.
 */
public final class ShopViews {

    private ShopViews() {
    }

    /** §10.5.3's {@code ShopSummary}: enough to draw a card, no contact, no licence detail. */
    public record Summary(
        String code,
        String name,
        String categorySlug,
        String categoryName,
        Integer wardNo,
        String wardName,
        List<String> fulfilmentOptions,
        String closingAt,
        boolean licenceVerified) {

        public static Summary of(Merchant merchant, String categorySlug, String categoryName,
                                 Integer wardNo, String wardName, String closingAt) {
            return new Summary(merchant.getCode(), merchant.getTradeName(), categorySlug, categoryName,
                wardNo, wardName, options(merchant), closingAt,
                merchant.getLicenceState() == com.saathi.merchant.domain.LicenceState.VALID);
        }
    }

    /** §10.5.3's {@code ShopDetail}: hours, wards served, licence badge, minimum basket. */
    public record Detail(
        String code,
        String name,
        String legalName,
        String categorySlug,
        String categoryName,
        Integer wardNo,
        String wardName,
        String address,
        String phone,
        String closingAt,
        List<Hours> hours,
        List<String> fulfilmentOptions,
        String defaultFulfilmentMode,
        boolean customerChoiceAllowed,
        MoneyView minOrder,
        boolean licenceVerified,
        long productCount) {
    }

    /** One line of the hours list. {@code day} is ISO-8601 (1 = Monday). */
    public record Hours(int day, String opensAt, String closesAt, boolean closed) {
    }

    /** §10.5.8's {@code ShopAdminView}: includes what a shop itself may not see. */
    public record Admin(
        String code,
        String tenantCode,
        String legalName,
        String tradeName,
        String categorySlug,
        String categoryName,
        Integer wardNo,
        String wardName,
        String phone,
        String state,
        String hiddenReason,
        String licenceRef,
        String licenceState,
        String fulfilmentCapability,
        String defaultFulfilmentMode,
        boolean customerChoiceAllowed,
        long productCount) {
    }

    /** The order this API ranks fulfilment options in when a shop supports both. */
    public static List<String> options(Merchant merchant) {
        return switch (merchant.getFulfilmentCapability()) {
            case PLATFORM_ONLY -> List.of(FulfilmentMode.PLATFORM.name());
            case MERCHANT_ONLY -> List.of(FulfilmentMode.MERCHANT.name());
            case BOTH -> List.of(FulfilmentMode.PLATFORM.name(), FulfilmentMode.MERCHANT.name());
        };
    }

    /** A shop whose delivery price is not known before a ward is chosen still needs a total shape. */
    public static MoneyView zero() {
        return MoneyView.of(Money.zero());
    }
}
