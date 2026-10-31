package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.repository.OrderEventRepository;
import com.saathi.order.repository.OrderLineRepository;
import com.saathi.order.repository.OrderRepository;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.stock.service.ReservationService;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.repository.ServiceWardRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.5.5's read surface, the render plumbing §10.5.6 shares, and the customer's one action, T5.
 *
 * <p>Every customer method resolves the order through {@code (tenant, customer, code)} rather than by
 * id: an order that belongs to someone else answers {@code NOT_FOUND}, the same refusal as one that
 * does not exist, so the endpoint never becomes a probe for other customers' order codes (§10.7).
 *
 * <p>{@link #summaries} and {@link #detailOf} take a {@link OrderViews.Viewer} instead of assuming
 * one, because the shop's queue and the customer's list are the same rows read by two entitlements
 * (§11.4): the shape is shared, the contacts and the action list are not.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderEventRepository events;
    private final MerchantDirectory directory;
    private final ServiceWardRepository wards;
    private final ReservationService reservations;
    private final OrderTrail trail;
    private final OrderViews views;
    private final OrderContacts contacts;
    private final QuoteService quotes;
    private final Clock clock;

    /** {@code GET /orders}: keyset-paged on {@code (placed_at DESC, id DESC)} (§10.3). */
    @Transactional(readOnly = true)
    public Page<OrderViews.Summary> list(AuthenticatedPrincipal caller, String status, String cursor,
                                         Integer limit) {
        caller.require("ORDER_READ_OWN");
        String tenantId = tenantOf(caller);
        int size = CursorPager.clampLimit(limit);
        After after = After.decode(cursor);
        OrderStatus wanted = statusFilter(status);

        List<Order> rows = orders.customerOrders(tenantId, caller.accountId(), wanted,
            after.filtered(), after.at(), after.id(),
            PageRequest.of(0, CursorPager.fetchSize(size)));
        boolean hasMore = rows.size() > size;
        List<Order> page = hasMore ? rows.subList(0, size) : rows;

        return Page.of(summaries(page, OrderViews.Viewer.CUSTOMER),
            After.next(page, hasMore), hasMore, size);
    }

    /**
     * One page rendered for one viewer. The shop, the ward and the pending-consent flag are fetched
     * per page rather than per row: {@code allowed_actions} is server-computed (§6.3) and needs all
     * three, and a page that asked for each per order would be three queries per row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OrderViews.Summary> summaries(List<Order> page, OrderViews.Viewer viewer) {
        Map<String, Merchant> merchants = new HashMap<>();
        Map<String, ServiceWard> wardRows = new HashMap<>();
        Set<String> waiting = quotes.pendingConsentOrderIds(
            page.stream().map(Order::getId).toList());
        return page.stream()
            .map(order -> views.summary(order, merchantOf(order, merchants),
                wardOf(order, wardRows), viewer, waiting.contains(order.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderViews.Detail detail(AuthenticatedPrincipal caller, String code) {
        caller.require("ORDER_READ_OWN");
        return detailOf(owned(caller, code), OrderViews.Viewer.CUSTOMER);
    }

    /**
     * The order as {@code viewer} is entitled to see it: lines, money, timeline, the consent request
     * it is waiting on, and §11.4's contacts for that viewer. MANDATORY because a read outside the
     * caller's transaction would render a total the write that follows is about to change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderViews.Detail detailOf(Order order, OrderViews.Viewer viewer) {
        Merchant merchant = directory.shopInTenant(order.getTenantId(), order.getMerchantId());
        ServiceWard ward = wardOf(order);
        return views.detail(order,
            lines.findByOrderIdOrderByIdAsc(order.getId()),
            events.findByOrderIdOrderByOccurredAtAscIdAsc(order.getId()),
            merchant, ward, viewer,
            activeQuoteOf(order, ward),
            contacts.forViewer(order, viewer));
    }

    @Transactional(readOnly = true)
    public OrderViews.Timeline timeline(AuthenticatedPrincipal caller, String code) {
        caller.require("ORDER_READ_OWN");
        Order order = owned(caller, code);
        return views.timeline(order,
            events.findByOrderIdOrderByOccurredAtAscIdAsc(order.getId()));
    }

    /**
     * T5 (§6.3): a customer may cancel while the shop has not accepted. At
     * {@code PRICE_CONFIRMATION_PENDING} the answer is {@code PRICE_CONFIRMATION_REQUIRED} — the
     * decline is the price-confirmation endpoint's job (§10.6.2), not this one's, and cancelling
     * here would skip the consent the shop's new total is waiting for.
     */
    @Transactional
    public OrderViews.Detail cancel(AuthenticatedPrincipal caller, String code, String reason,
                                    String requestId) {
        caller.require("ORDER_CANCEL_OWN");
        Order order = owned(caller, code);
        if (order.getStatus() == OrderStatus.PRICE_CONFIRMATION_PENDING) {
            throw new ApiException(ErrorCode.PRICE_CONFIRMATION_REQUIRED,
                "Respond to the shop's price update before cancelling.",
                Map.of("allowed_actions",
                    views.allowedActions(order, OrderViews.Viewer.CUSTOMER, true)));
        }
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                "This order can no longer be cancelled.",
                Map.of("status", order.getStatus().name(),
                    // Nothing reaches this branch with a live consent quote: an order quote is
                    // issued after acceptance, and the two statuses that can hold one are refused
                    // above and by the transition gate.
                    "allowed_actions",
                    views.allowedActions(order, OrderViews.Viewer.CUSTOMER, false)));
        }

        String note = reason == null || reason.isBlank() ? null : reason.trim();
        order.setCancelledAt(clock.instant());
        order.setCancelReason(note);
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.CANCELLED)
            .kind(OrderEventKind.CANCELLATION)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_CANCEL_OWN")
            .note(note)
            .requestId(requestId)
            .outboxType("ORDER_CANCELLED")
            .extras(note == null ? null : Map.of("reason", note))
            .build());
        reservations.releaseAll(order.getTenantId(), order.getId());

        return detailOf(order, OrderViews.Viewer.CUSTOMER);
    }

    /** §10.4's {@code active_quote}: the consent request this order is waiting on, if any. */
    private QuoteViews.QuoteView activeQuoteOf(Order order, ServiceWard ward) {
        return quotes.awaitingOrderQuote(order.getId())
            .map(snapshot -> quotes.render(snapshot,
                new QuoteViews.Ward(ward.getWardNo(), ward.getName())))
            .orElse(null);
    }

    private Order owned(AuthenticatedPrincipal caller, String code) {
        String tenantId = tenantOf(caller);
        return orders.findByTenantIdAndCustomerIdAndCode(tenantId, caller.accountId(), code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                "We could not find that order."));
    }

    private static String tenantOf(AuthenticatedPrincipal caller) {
        if (caller.kind() != PrincipalKind.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Orders here belong to a customer.");
        }
        if (caller.tenantId() == null || caller.tenantId().isBlank()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "Choose your area before you open your orders.");
        }
        return caller.tenantId();
    }

    /**
     * §6.1's status filter. Package-private and static because the shop's queue filters by the same
     * closed list with the same refusal; a second copy would drift the wording of one validation error
     * from the other (§12.4's identical messaging for every cause).
     */
    static OrderStatus statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status is not a recognised state",
                Map.of("field_errors", List.of(Map.of("field", "status",
                    "message", "must be one of the §6.1 states"))));
        }
    }

    /** A decoded keyset position, and the two lists' shared answer to a cursor they cannot read. */
    record After(Instant at, String id) {

        /** The newest row is where a page starts when there is no cursor; a half cursor is a bug. */
        static After decode(String cursor) {
            List<String> keys = CursorPager.decode(cursor);
            if (!keys.isEmpty() && keys.size() != 2) {
                throw CursorPager.badCursor();
            }
            return keys.isEmpty() ? new After(null, null)
                : new After(instant(keys.get(0)), keys.get(1));
        }

        boolean filtered() {
            return at != null;
        }

        /** The cursor for the next page, or null on the last one. */
        static String next(List<Order> page, boolean hasMore) {
            if (!hasMore || page.isEmpty()) {
                return null;
            }
            Order last = page.get(page.size() - 1);
            return CursorPager.encode(List.of(last.getPlacedAt().toString(), last.getId()));
        }

        private static Instant instant(String encoded) {
            try {
                return Instant.parse(encoded);
            } catch (DateTimeParseException e) {
                throw CursorPager.badCursor();
            }
        }
    }

    private Merchant merchantOf(Order order, Map<String, Merchant> cache) {
        return cache.computeIfAbsent(order.getMerchantId(),
            id -> directory.shopInTenant(order.getTenantId(), id));
    }

    private ServiceWard wardOf(Order order, Map<String, ServiceWard> cache) {
        return cache.computeIfAbsent(order.getWardId(), id -> wards.findById(id)
            .orElseThrow(() -> new IllegalStateException(
                "order " + order.getCode() + " names a ward that does not exist")));
    }

    private ServiceWard wardOf(Order order) {
        return wardOf(order, new HashMap<>());
    }
}
