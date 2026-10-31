package com.saathi.order.service;

import com.saathi.merchant.domain.Merchant;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEvent;
import com.saathi.order.domain.OrderLine;
import com.saathi.order.domain.OrderStatus;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import com.saathi.tenancy.domain.ServiceWard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * §10.4's order shapes, built in one place.
 *
 * <p>{@code allowed_actions} is server-computed (§6.3) and lists only actions this build actually
 * serves, for the caller in front of it: a client that trusts the list must never be offered a
 * button that answers 404, and a customer must not be told a shop's {@code accept} exists. The
 * viewer is therefore an argument, not a guess the client makes from its own permission list.
 *
 * <p>{@code contacts} is §11.4's progressive disclosure in one record. The delivery address is
 * whatever the viewer is entitled to read — the customer's own, or the shop's assigned order — and
 * the phone is full only where the table grants it, so a masked value and an absent value never have
 * to be told apart by the client.
 */
@Component
public class OrderViews {

    public enum Viewer {
        CUSTOMER,
        SHOP
    }

    /** The order's own weight quote or pending fulfilment change, rendered by {@code quote/}. */
    public record Placement(Detail order, AcceptedQuote quote) {
    }

    /** The quote as accepted by the placement, echoed beside the order (§10.6.1). */
    public record AcceptedQuote(String id, String state, Instant acceptedAt) {
    }

    /**
     * The order plus the quote an action just created (§10.5.6's {@code {order, quote}} for weigh and
     * fulfilment reselect). The shop's scales and the mode selector must show the customer the same
     * numbers the order will carry once they consent, so both halves leave in one response.
     */
    public record WithQuote(Detail order, QuoteViews.QuoteView quote) {
    }

    public record Summary(
        String code,
        String status,
        Shop merchant,
        Ward ward,
        Instant placedAt,
        MoneyView total,
        String paymentStatus,
        String collectionChannel,
        String fulfilmentModeSnapshot,
        List<String> allowedActions) {
    }

    public record Detail(
        String code,
        String status,
        Shop merchant,
        Ward ward,
        Instant placedAt,
        MoneyView total,
        String paymentStatus,
        String collectionChannel,
        String fulfilmentModeSnapshot,
        List<Line> lines,
        MoneyBreakdown money,
        List<TimelineEntry> timeline,
        QuoteViews.QuoteView activeQuote,
        List<String> allowedActions,
        Contacts contacts) {
    }

    public record Shop(String code, String name) {
    }

    public record Ward(int wardNo, String name) {
    }

    public record Line(
        String id,
        String productCode,
        String name,
        String unitLabel,
        BigDecimal qtyOrdered,
        BigDecimal qtyWeighed,
        BigDecimal qtyDelivered,
        MoneyView unitPrice,
        MoneyView lineTotal,
        String priceSource) {
    }

    public record MoneyBreakdown(
        MoneyView goods,
        MoneyView deliveryFee,
        MoneyView handlingFee,
        MoneyView total,
        MoneyView collected,
        MoneyView outstanding,
        MoneyView commission) {
    }

    public record TimelineEntry(
        String kind,
        String statusFrom,
        String statusTo,
        Instant at,
        Actor actor,
        String note) {
    }

    public record Actor(String type, String label) {
    }

    public record Contacts(String merchantPhoneMasked, String revealUrl, String customerName,
                           String customerPhone, String address) {
    }

    public record Timeline(String orderCode, List<TimelineEntry> events) {
    }

    /**
     * {@code OrderSummary} for either list — the customer's own orders and the shop's queue are the
     * same shape with a different viewer, which is what keeps the two {@code allowed_actions} lists
     * from being reconciled by a client.
     *
     * @param consentPending whether an unanswered quote exists for this order
     */
    public Summary summary(Order order, Merchant merchant, ServiceWard ward, Viewer viewer,
                           boolean consentPending) {
        return new Summary(
            order.getCode(),
            order.getStatus().name(),
            shop(merchant),
            ward(ward),
            order.getPlacedAt(),
            MoneyView.of(Money.ofPaise(order.getTotalPaise())),
            order.getPaymentStatus().name(),
            order.getCollectionChannel().name(),
            order.getFulfilmentModeSnapshot(),
            allowedActions(order, viewer, consentPending));
    }

    /**
     * {@code OrderSummary}, then the three things only a detail carries: the lines, the money
     * breakdown and the timeline. {@code activeQuote} is §10.4's pending consent request — the weight
     * quote the customer has not answered, or a fulfilment change that raised the total — and it is
     * the reason a client can render {@code price_confirmation} without asking a second endpoint what
     * the shop changed.
     */
    public Detail detail(Order order, List<OrderLine> lines, List<OrderEvent> events,
                         Merchant merchant, ServiceWard ward, Viewer viewer,
                         QuoteViews.QuoteView activeQuote, OrderContacts.Viewed contacts) {
        return new Detail(
            order.getCode(),
            order.getStatus().name(),
            shop(merchant),
            ward(ward),
            order.getPlacedAt(),
            MoneyView.of(Money.ofPaise(order.getTotalPaise())),
            order.getPaymentStatus().name(),
            order.getCollectionChannel().name(),
            order.getFulfilmentModeSnapshot(),
            lines.stream().map(OrderViews::line).toList(),
            new MoneyBreakdown(
                MoneyView.of(Money.ofPaise(order.getGoodsPaise())),
                MoneyView.of(Money.ofPaise(order.getDeliveryFeePaise())),
                MoneyView.of(Money.ofPaise(order.getHandlingFeePaise())),
                MoneyView.of(Money.ofPaise(order.getTotalPaise())),
                MoneyView.of(Money.ofPaise(order.getCollectedPaise())),
                MoneyView.of(Money.ofPaise(order.getOutstandingPaise())),
                MoneyView.of(Money.ofPaise(order.getCommissionPaise()))),
            events.stream().map(OrderViews::entry).toList(),
            activeQuote,
            allowedActions(order, viewer, activeQuote != null),
            new Contacts(PhoneNormaliser.mask(merchant.getPhone()), null,
                contacts.name(), contacts.phone(), contacts.address()));
    }

    /** §10.5.5's timeline: the append-only trail, one entry per event, oldest first. */
    public Timeline timeline(Order order, List<OrderEvent> events) {
        return new Timeline(order.getCode(), events.stream().map(OrderViews::entry).toList());
    }

    /**
     * The action names are the endpoint slugs, so a client can build a button from this list without
     * knowing anything else about the API. Every edge of §6.3 that this build serves appears here for
     * its actor and nothing else: {@code delivered_self} waits for the accrual module, and the job
     * actions wait for {@code delivery/}, because offering a route that answers 404 would make the
     * server-computed list §6.3 promises into a suggestion.
     *
     * @param consentPending whether this order has an unanswered quote, which is a gate §6.3 states
     *                       as a condition rather than as a status: T6 refuses while a by-weight line
     *                       or a §6.2 reselect waits, and the order's own status says nothing about it
     */
    public List<String> allowedActions(Order order, Viewer viewer, boolean consentPending) {
        OrderStatus status = order.getStatus();
        if (order.isTerminal()) {
            return List.of();
        }
        return switch (viewer) {
            case CUSTOMER -> customerActions(status, consentPending);
            case SHOP -> shopActions(order, status, consentPending);
        };
    }

    /**
     * The customer answers a consent request from either status that can hold one. T7 moves the order
     * to {@code PRICE_CONFIRMATION_PENDING}; §6.2's reselect leaves it at {@code MERCHANT_ACCEPTED},
     * because the shop changed the mode and the customer has to agree before the order starts being
     * prepared. Both are the same endpoint (§10.6.2), so both are the same action name.
     */
    private static List<String> customerActions(OrderStatus status, boolean consentPending) {
        if (status == OrderStatus.PRICE_CONFIRMATION_PENDING
            || (consentPending && status == OrderStatus.MERCHANT_ACCEPTED)) {
            return List.of("price_confirmation");
        }
        return status == OrderStatus.PENDING_CONFIRMATION ? List.of("cancel") : List.of();
    }

    private static List<String> shopActions(Order order, OrderStatus status,
                                            boolean consentPending) {
        boolean platform = "PLATFORM".equals(order.getFulfilmentModeSnapshot());
        return switch (status) {
            case PENDING_CONFIRMATION -> List.of("accept", "reject");
            // §6.2: the mode is still selectable until the order starts being prepared.
            case MERCHANT_ACCEPTED -> consentPending
                // T6's gate, and the reason re-selecting and weighing stay open: a fresh quote
                // supersedes the waiting one rather than adding a second answer to the same order.
                ? List.of("weigh", "cancel", "fulfilment_mode")
                : List.of("prepare", "weigh", "cancel", "fulfilment_mode");
            case PRICE_CONFIRMATION_PENDING -> List.of("cancel");
            case PREPARING -> platform
                ? List.of("ready", "cancel")
                : List.of("dispatch_self", "cancel");
            case READY_FOR_PICKUP -> List.of();
            case OUT_FOR_DELIVERY -> List.of("fail_delivery");
            // T18b: a failed self-delivery is re-attempted by the same action that sent it.
            case DELIVERY_FAILED -> platform ? List.of() : List.of("dispatch_self");
            default -> List.of();
        };
    }

    private static Shop shop(Merchant merchant) {
        return new Shop(merchant.getCode(), merchant.getTradeName());
    }

    private static Ward ward(ServiceWard ward) {
        return new Ward(ward.getWardNo(), ward.getName());
    }

    private static Line line(OrderLine line) {
        boolean weighed = line.getQtyWeighed() != null;
        return new Line(
            line.getId(),
            line.getProductCode(),
            line.getNameSnapshot(),
            line.getUnitLabelSnapshot(),
            line.getQtyOrdered(),
            line.getQtyWeighed(),
            line.getQtyDelivered(),
            MoneyView.of(Money.ofPaise(line.getUnitPricePaise()), weighed),
            MoneyView.of(Money.ofPaise(line.getLinePaise()), weighed),
            line.getPriceSource().name());
    }

    private static TimelineEntry entry(OrderEvent event) {
        return new TimelineEntry(
            event.getKind().name(),
            event.getFromStatus() == null ? null : event.getFromStatus().name(),
            event.getToStatus().name(),
            event.getOccurredAt(),
            new Actor(event.getActorType().name(), label(event)),
            event.getNote());
    }

    private static String label(OrderEvent event) {
        return switch (event.getActorType()) {
            case CUSTOMER -> "You";
            case SYSTEM -> "Saathi";
            case MERCHANT_STAFF -> "Shop";
            case RIDER -> "Rider";
            case OPERATOR -> "Support";
            case SUPER_ADMIN -> "Saathi admin";
        };
    }
}
