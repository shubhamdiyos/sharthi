package com.saathi.order.service;

import com.saathi.merchant.domain.Merchant;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEvent;
import com.saathi.order.domain.OrderLine;
import com.saathi.order.domain.OrderStatus;
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
 * serves: a client that trusts the list must never be offered a button that answers 404.
 */
@Component
public class OrderViews {

    public record Placement(Detail order, AcceptedQuote quote) {
    }

    /** The quote as accepted by the placement, echoed beside the order (§10.6.1). */
    public record AcceptedQuote(String id, String state, Instant acceptedAt) {
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

    public record Contacts(String merchantPhoneMasked, String revealUrl) {
    }

    public record Timeline(String orderCode, List<TimelineEntry> events) {
    }

    public Summary summary(Order order, Merchant merchant, ServiceWard ward) {
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
            allowedActions(order));
    }

    public Detail detail(Order order, List<OrderLine> lines, List<OrderEvent> events,
                         Merchant merchant, ServiceWard ward) {
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
            allowedActions(order),
            new Contacts(PhoneNormaliser.mask(merchant.getPhone()), null));
    }

    /** §10.5.5's timeline: the append-only trail, one entry per event, oldest first. */
    public Timeline timeline(Order order, List<OrderEvent> events) {
        return new Timeline(order.getCode(), events.stream().map(OrderViews::entry).toList());
    }

    /** T5 is the only customer action this build serves; the rest arrive with their endpoints. */
    public List<String> allowedActions(Order order) {
        if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION) {
            return List.of("cancel");
        }
        return List.of();
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
