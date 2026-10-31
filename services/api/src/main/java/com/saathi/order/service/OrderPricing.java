package com.saathi.order.service;

import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderLine;
import com.saathi.quote.service.QuoteInput;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.money.Money;
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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an existing order is worth under one proposed change (§6.3's T7 and §6.2's reselect).
 *
 * <p>The mirror of {@code cart/}'s {@code CartPricing}, and deliberately separate from it: a cart is
 * a basket of live products whose prices are read from the catalogue, while an order is a historical
 * artifact whose unit prices are the ones the customer was quoted. Reading {@code product.price}
 * here would let a catalogue edit change what a shop's weighing scales produce, which §7.4 forbids —
 * "merchants do not edit an order's price at acceptance". Every unit price below comes from
 * {@code order_line}, and the only things a caller may supply are quantities and a fulfilment mode.
 *
 * <p>Both re-prices produce a quote with {@code ttlMinutes} null. A quote that carries a consent
 * request cannot expire (§6.5 forbids a price-confirmation countdown): an expired one would leave the
 * order with a total the customer is no longer allowed to accept and no edge out except cancellation.
 */
@Service
@RequiredArgsConstructor
public class OrderPricing {

    private final ServiceabilityService serviceability;

    /** A re-price. The caller compares {@code totalPaise} with the order to decide what consent asks. */
    public record Repriced(
        QuoteInput input,
        long goodsPaise,
        long deliveryFeePaise,
        long handlingFeePaise,
        long totalPaise) {

        public boolean risesAgainst(Order order) {
            return totalPaise > order.getTotalPaise();
        }
    }

    /**
     * T7: the weighed quantities against the quoted unit prices. A line the shop did not weigh keeps
     * the quantity the customer asked for, so a partial weigh cannot silently re-price the rest, and
     * every line that now costs more carries its own {@code WEIGHT_FINAL_PRICE} requirement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Repriced weighed(Tenant tenant, Order order, ServiceWard ward, List<OrderLine> lines,
                            Map<String, BigDecimal> qtyWeighedByLine, Instant issuedAt) {
        List<QuoteInput.Line> quoteLines = new ArrayList<>(lines.size());
        List<QuoteInput.Consent> consents = new ArrayList<>();
        long goods = 0;
        for (OrderLine line : lines) {
            BigDecimal weighed = qtyWeighedByLine.get(line.getId());
            BigDecimal charged = weighed == null ? line.getQtyOrdered() : weighed;
            long linePaise = QuoteViews.linePaise(charged, line.getUnitPricePaise());
            long delta = linePaise - line.getLinePaise();
            goods = Math.addExact(goods, linePaise);
            quoteLines.add(new QuoteInput.Line(line.getProductId(), line.getProductCode(),
                line.getNameSnapshot(), line.getUnitLabelSnapshot(), line.getQtyOrdered(),
                weighed, line.getUnitPricePaise(), delta, delta > 0));
            if (delta > 0) {
                consents.add(new QuoteInput.Consent("WEIGHT_FINAL_PRICE", line.getProductId(),
                    line.getLinePaise(), linePaise));
            }
        }
        return price(tenant, order, ward, com.saathi.quote.domain.QuoteScope.WEIGHT,
            FulfilmentMode.valueOf(order.getFulfilmentModeSnapshot()), goods,
            List.copyOf(quoteLines), List.copyOf(consents), issuedAt);
    }

    /**
     * §6.2: the same goods under the other mode's fee rule. Nothing about a line changes, so the
     * consent requirement is order-level — no product, and the from/to are the two totals.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Repriced modeChange(Tenant tenant, Order order, ServiceWard ward, List<OrderLine> lines,
                               FulfilmentMode newMode, Instant issuedAt) {
        List<QuoteInput.Line> quoteLines = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            BigDecimal charged = line.getQtyWeighed() == null
                ? line.getQtyOrdered() : line.getQtyWeighed();
            quoteLines.add(new QuoteInput.Line(line.getProductId(), line.getProductCode(),
                line.getNameSnapshot(), line.getUnitLabelSnapshot(), line.getQtyOrdered(),
                line.getQtyWeighed(), line.getUnitPricePaise(),
                QuoteViews.linePaise(charged, line.getUnitPricePaise()) - line.getLinePaise(),
                false));
        }
        long goods = order.getGoodsPaise();
        long proposed = proposedTotal(feeRule(tenant, ward, newMode), goods);
        List<QuoteInput.Consent> consents = proposed > order.getTotalPaise()
            ? List.of(new QuoteInput.Consent("FULFILMENT_MODE_CHANGE", null,
                order.getTotalPaise(), proposed))
            : List.of();
        return price(tenant, order, ward, com.saathi.quote.domain.QuoteScope.FULFILMENT_CHANGE,
            newMode, goods, List.copyOf(quoteLines), consents, issuedAt);
    }

    /**
     * The ward's rule for one mode. A city that prices neither is a configuration the operator
     * repairs, and §10.5.6 answers it with {@code MODE_NOT_AVAILABLE} rather than pretending the
     * mode exists with a zero fee.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WardFeeRule feeRule(Tenant tenant, ServiceWard ward, FulfilmentMode mode) {
        for (WardFeeRule rule : serviceability.effectiveFees(tenant, ward)) {
            if (rule.getFulfilmentMode() == mode) {
                return rule;
            }
        }
        throw new ApiException(ErrorCode.MODE_NOT_AVAILABLE,
            "That delivery choice is not available in this area.");
    }

    private Repriced price(Tenant tenant, Order order, ServiceWard ward,
                           com.saathi.quote.domain.QuoteScope scope, FulfilmentMode mode,
                           long goods, List<QuoteInput.Line> quoteLines,
                           List<QuoteInput.Consent> consents, Instant issuedAt) {
        WardFeeRule rule = feeRule(tenant, ward, mode);
        long total = proposedTotal(rule, goods);
        QuoteInput input = new QuoteInput(tenant.getId(), null, order.getId(), scope, mode.name(),
            rule.getDeliveryFeePaise(), rule.getHandlingFeePaise(),
            Rate.ofBasisPoints(order.getCommissionBpSnapshot()).applyTo(Money.ofPaise(goods)).paise(),
            null, issuedAt, quoteLines, consents, options(tenant, ward, goods));
        return new Repriced(input, goods, rule.getDeliveryFeePaise(), rule.getHandlingFeePaise(),
            total);
    }

    /** Both modes' totals, frozen with the quote, so a later fee edit cannot move the choice (§5.2). */
    private List<QuoteInput.Option> options(Tenant tenant, ServiceWard ward, long goods) {
        Map<FulfilmentMode, WardFeeRule> rules = new EnumMap<>(FulfilmentMode.class);
        for (WardFeeRule rule : serviceability.effectiveFees(tenant, ward)) {
            rules.put(rule.getFulfilmentMode(), rule);
        }
        List<QuoteInput.Option> options = new ArrayList<>(2);
        for (FulfilmentMode mode : FulfilmentMode.values()) {
            WardFeeRule rule = rules.get(mode);
            if (rule != null) {
                options.add(new QuoteInput.Option(mode.name(), proposedTotal(rule, goods), null));
            }
        }
        return List.copyOf(options);
    }

    private static long proposedTotal(WardFeeRule rule, long goods) {
        return Math.addExact(goods,
            Math.addExact(rule.getDeliveryFeePaise(), rule.getHandlingFeePaise()));
    }
}
