package com.saathi.tenancy.web;

import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.tenancy.domain.WardFeeRule;
import java.util.List;
import java.util.Map;

/**
 * §10.5.2's {@code ServiceabilityView}: the answer a storefront needs before it shows a cart, in one
 * call — which city was resolved, which ward of it, what a delivery costs there, and which kill
 * switches are up.
 *
 * <p><b>What the document asks for and this does not carry: an open-shop count.</b> §10.5.2 describes
 * "resolved tenant + ward + per-mode fees + open-shop count", and there is no {@code merchant} table
 * in the schema yet, so a count here would be a number with no source. It is absent rather than
 * zero: a {@code 0} would be read as "this ward has no shops" and send a customer to another city.
 *
 * <p>{@code flags} is additive to §10.5.2's sentence and exists because PRD §12's switches decide
 * what the storefront may offer (COD only, no payment gateway, no in-app chat), and a guest has no
 * {@code PrincipalView} to learn them from — §10.4's "the client's only source of permissions" is
 * unreachable before sign-in.
 *
 * @param fees one entry per fulfilment mode this ward actually has a live rule for; a ward with no
 *             rule for a mode cannot be quoted in it, so the mode is absent rather than free
 */
public record ServiceabilityView(CitySummary city, WardSummary ward, List<Fee> fees, Map<String, Boolean> flags) {

    /** One mode's cost to serve this ward, in §10.4's Money shape. */
    public record Fee(String fulfilmentMode, MoneyView deliveryFee, MoneyView handlingFee, MoneyView minOrder) {

        public static Fee of(WardFeeRule rule) {
            return new Fee(rule.getFulfilmentMode().name(),
                MoneyView.of(Money.ofPaise(rule.getDeliveryFeePaise())),
                MoneyView.of(Money.ofPaise(rule.getHandlingFeePaise())),
                MoneyView.of(Money.ofPaise(rule.getMinOrderPaise())));
        }
    }
}
