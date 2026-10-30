package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEvent;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.repository.OrderEventRepository;
import com.saathi.order.repository.OrderLineRepository;
import com.saathi.order.repository.OrderRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.outbox.OutboxRecorder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * §10.5.5's read surface and the customer's one action, T5.
 *
 * <p>Every method resolves the order through {@code (tenant, customer, code)} rather than by id:
 * an order that belongs to someone else answers {@code NOT_FOUND}, the same refusal as one that
 * does not exist, so the endpoint never becomes a probe for other customers' order codes (§10.7).
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
    private final OutboxRecorder outbox;
    private final OrderViews views;
    private final ObjectMapper json;
    private final Clock clock;

    /** {@code GET /orders}: keyset-paged on {@code (placed_at DESC, id DESC)} (§10.3). */
    @Transactional(readOnly = true)
    public Page<OrderViews.Summary> list(AuthenticatedPrincipal caller, String status, String cursor,
                                         Integer limit) {
        caller.require("ORDER_READ_OWN");
        String tenantId = tenantOf(caller);
        int size = CursorPager.clampLimit(limit);
        List<String> keys = CursorPager.decode(cursor);
        if (!keys.isEmpty() && keys.size() != 2) {
            throw CursorPager.badCursor();
        }
        Instant afterAt = keys.isEmpty() ? null : instant(keys.get(0));
        String afterId = keys.isEmpty() ? null : keys.get(1);
        OrderStatus wanted = statusFilter(status);

        List<Order> rows = orders.customerOrders(tenantId, caller.accountId(), wanted,
            !keys.isEmpty(), afterAt, afterId, PageRequest.of(0, CursorPager.fetchSize(size)));
        boolean hasMore = rows.size() > size;
        List<Order> page = hasMore ? rows.subList(0, size) : rows;

        Map<String, Merchant> merchants = new HashMap<>();
        Map<String, ServiceWard> wardRows = new HashMap<>();
        List<OrderViews.Summary> data = page.stream()
            .map(order -> views.summary(order, merchantOf(order, merchants), wardOf(order, wardRows)))
            .toList();
        Order last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = hasMore
            ? CursorPager.encode(List.of(last.getPlacedAt().toString(), last.getId()))
            : null;
        return Page.of(data, next, hasMore, size);
    }

    @Transactional(readOnly = true)
    public OrderViews.Detail detail(AuthenticatedPrincipal caller, String code) {
        caller.require("ORDER_READ_OWN");
        Order order = owned(caller, code);
        return detailOf(order);
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
                Map.of("allowed_actions", views.allowedActions(order)));
        }
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                "This order can no longer be cancelled.",
                Map.of("status", order.getStatus().name(),
                    "allowed_actions", views.allowedActions(order)));
        }

        String note = reason == null || reason.isBlank() ? null : reason.trim();
        Instant now = clock.instant();
        OrderStatus from = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(now);
        order.setCancelReason(note);
        orders.saveAndFlush(order);

        OrderEvent cancellation = event(order, from, OrderEventKind.CANCELLATION,
            caller.accountId(), "ORDER_CANCEL_OWN", now, note, requestId);
        events.saveAndFlush(cancellation);
        reservations.releaseAll(order.getTenantId(), order.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", order.getId());
        payload.put("code", order.getCode());
        payload.put("total_paise", order.getTotalPaise());
        payload.put("reason", note);
        outbox.record(order.getTenantId(), "order", order.getId(), "ORDER_CANCELLED",
            json.writeValueAsString(payload));

        return detailOf(order);
    }

    private OrderViews.Detail detailOf(Order order) {
        Merchant merchant = directory.shopInTenant(order.getTenantId(), order.getMerchantId());
        return views.detail(order,
            lines.findByOrderIdOrderByIdAsc(order.getId()),
            events.findByOrderIdOrderByOccurredAtAscIdAsc(order.getId()),
            merchant, wardOf(order));
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

    private static OrderStatus statusFilter(String status) {
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

    private static Instant instant(String encoded) {
        try {
            return Instant.parse(encoded);
        } catch (DateTimeParseException e) {
            throw CursorPager.badCursor();
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

    private OrderEvent event(Order order, OrderStatus from, OrderEventKind kind, String actorId,
                             String permission, Instant at, String note, String requestId) {
        return OrderEvent.builder()
            .id(Ulid.next(clock))
            .tenantId(order.getTenantId())
            .orderId(order.getId())
            .fromStatus(from)
            .toStatus(OrderStatus.CANCELLED)
            .kind(kind)
            .actorType(PrincipalKind.CUSTOMER)
            .actorId(actorId)
            .permissionUsed(permission)
            .occurredAt(at)
            .note(note)
            .requestId(requestId)
            .build();
    }
}
