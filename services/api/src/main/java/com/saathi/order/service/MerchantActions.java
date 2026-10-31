package com.saathi.order.service;

import com.saathi.catalog.domain.Product;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.order.domain.CollectionChannel;
import com.saathi.order.domain.FailReason;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderLine;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.domain.RejectReason;
import com.saathi.order.repository.OrderLineRepository;
import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteSnapshot;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.stock.domain.StockPolicy;
import com.saathi.stock.service.ReservationService;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.ServiceabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop's half of §6.3: T3, T4, T6, T7, T11, T12, T17, T18b and T10, plus §6.2's reselect.
 *
 * <p>One method per action, and every one of them is the same three steps — the shop's own order
 * under its own permission, the edge's own gate, then {@link OrderTrail} — because the document's
 * table is the specification and a shape invented around it is how an edge drifts from its side
 * effects. Nothing here takes a status: clients send actions (§10.7), and the only way an order
 * moves is the edge this method knows how to take.
 *
 * <p>A refusal is {@code 409 INVALID_TRANSITION} carrying the action set the current status does
 * allow (§6.3's last paragraph), so a shop's screen disables the button it just pressed rather than
 * guessing from the status name.
 */
@Service
@RequiredArgsConstructor
public class MerchantActions {

    /**
     * §6.2's "before the lock". The document gives both a status list and three lock triggers; the
     * list is what this build can enforce, because {@code fulfilment_locked_at} is stamped at
     * placement (§6.2's column is {@code NOT NULL from creation}) and {@code delivery_job} does not
     * exist yet. The three statuses are exactly the ones where no trigger can have fired: no job, no
     * {@code OUT_FOR_DELIVERY}, no {@code PREPARING}.
     */
    private static final Set<OrderStatus> PRE_LOCK = EnumSet.of(OrderStatus.CREATED,
        OrderStatus.PENDING_CONFIRMATION, OrderStatus.MERCHANT_ACCEPTED);

    private final OrderScope scope;
    private final OrderLineRepository lines;
    private final OrderQueryService reads;
    private final OrderViews views;
    private final OrderTrail trail;
    private final OrderStock stock;
    private final OrderPricing pricing;
    private final QuoteService quotes;
    private final ReservationService reservations;
    private final MerchantDirectory directory;
    private final ServiceabilityService serviceability;
    private final Clock clock;

    /**
     * T3. §7.4-3 makes acceptance the third revalidation point: the shop is asserting it has the
     * goods, so availability is re-checked under the product locks before the order moves, and a
     * city that reserves on acceptance takes its hold here rather than at placement.
     */
    @Transactional
    public OrderViews.Detail accept(AuthenticatedPrincipal caller, String code, String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_ACCEPT", code);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw illegal(order, "This order is no longer waiting for the shop.");
        }
        Merchant shop = directory.shopInTenant(order.getTenantId(), order.getMerchantId());
        if (!shop.isVisible()) {
            throw new ApiException(ErrorCode.MERCHANT_UNAVAILABLE,
                "This shop is not taking orders right now.");
        }
        List<OrderLine> rows = lines.findByOrderIdOrderByIdAsc(order.getId());
        Tenant tenant = serviceability.tenantById(order.getTenantId());
        stock.assertStillSellable(tenant.getId(), rows);
        stock.holdFor(StockPolicy.RESERVE_ON_ACCEPT, tenant, shop.getId(), rows);

        order.setAcceptedAt(clock.instant());
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.MERCHANT_ACCEPTED)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_ACCEPT")
            .requestId(requestId)
            .outboxType("ORDER_ACCEPTED")
            .build());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /** T4. The reason is mandatory and closed: the customer is shown it (PRD §8.5). */
    @Transactional
    public OrderViews.Detail reject(AuthenticatedPrincipal caller, String code, String reason,
                                    String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_REJECT", code);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw illegal(order, "This order is no longer waiting for the shop.");
        }
        RejectReason why = RejectReason.parse(reason);
        order.setRejectReason(why.name());
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.REJECTED)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_REJECT")
            .note(why.name())
            .requestId(requestId)
            .outboxType("ORDER_REJECTED")
            .reasonCode(why.name())
            .extras(Map.of("reject_reason", why.name()))
            .auditAfter(Map.of("status", OrderStatus.REJECTED.name(), "reject_reason", why.name()))
            .build());
        reservations.releaseAll(order.getTenantId(), order.getId());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /**
     * T6. The gate is not a status: a weight quote or a fulfilment change can still be waiting on
     * the customer, and §10.5.6 answers that with {@code PRICE_CONFIRMATION_REQUIRED} rather than
     * letting a shop start cooking a total nobody has agreed to pay.
     */
    @Transactional
    public OrderViews.Detail prepare(AuthenticatedPrincipal caller, String code, String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_PREPARE", code);
        // The consent gate is checked before the status gate on purpose. T7 leaves the order at
        // PRICE_CONFIRMATION_PENDING, which is a status T6 cannot take, but §10.5.6 names
        // PRICE_CONFIRMATION_REQUIRED for it: "the customer has not answered" is the fact the shop
        // needs, and INVALID_TRANSITION would send it to a state machine it cannot act on.
        requireNoConsentOutstanding(order);
        if (order.getStatus() != OrderStatus.MERCHANT_ACCEPTED) {
            throw illegal(order, "This order is not waiting to be prepared.");
        }
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.PREPARING)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_PREPARE")
            .requestId(requestId)
            .build());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /**
     * T7 (§10.6.3): the shop's scales, never its keyboard. The only input is a weighed quantity per
     * by-weight line; the total is computed from the order's own unit prices by {@link OrderPricing}
     * and left as a quote for the customer to answer, so nothing here touches the order's money —
     * §6.3 makes T8 the one edge that rewrites it.
     *
     * <p>Every by-weight line must be weighed. A partial weigh would stage a measured price for the
     * lines the shop put on the scale and the estimate for the rest, and §8.4's rule is that no
     * number the customer is charged may be unexplained.
     *
     * <p>A bag over §7.2's tolerance is a 422, not a bigger quote: the platform reserved exactly that
     * ceiling and sold it to this order, so more than it is goods the platform never held. The
     * shop's way out is the reason-bearing T9b cancel, which is what T9b's own gate names.
     */
    @Transactional
    public OrderViews.WithQuote weigh(AuthenticatedPrincipal caller, String code, WeighBody body,
                                      String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_WEIGH", code);
        if (order.getStatus() != OrderStatus.MERCHANT_ACCEPTED) {
            throw illegal(order, "This order is not waiting to be weighed.");
        }
        String note = trimmed(body == null ? null : body.note());
        if (note == null) {
            // WEIGHED is one of §13.2's ⚑ actions: the audit row needs the shop's own words, and an
            // API that invented a reason would be recording evidence it does not have.
            throw fieldError("note", "is required — say what the scales showed");
        }
        List<OrderLine> rows = lines.findByOrderIdOrderByIdAsc(order.getId());
        Tenant tenant = serviceability.tenantById(order.getTenantId());
        Map<String, Product> products = stock.byId(tenant.getId(), rows);
        List<OrderLine> byWeight = rows.stream()
            .filter(row -> products.get(row.getProductId()).isByWeight())
            .toList();
        if (byWeight.isEmpty()) {
            throw illegal(order, "This order has nothing to weigh — prepare it instead.");
        }

        Map<String, BigDecimal> qtyWeighed = weighedQuantities(body, byWeight, rows);
        ServiceWard ward = serviceability.wardById(tenant, order.getWardId());
        int tolerance = WeightTolerance.basisPoints(tenant);
        for (OrderLine row : byWeight) {
            BigDecimal qty = qtyWeighed.get(row.getId());
            if (!WeightTolerance.isWithin(row.getQtyOrdered(), qty, tolerance)) {
                throw outOfTolerance(row, qty, tolerance);
            }
        }

        OrderPricing.Repriced repriced = pricing.weighed(tenant, order, ward, rows, qtyWeighed,
            clock.instant());
        QuoteSnapshot quote = quotes.issue(repriced.input());
        // §7.2: the true-up is the only place a reservation changes size, and it is trued to what
        // the scales said rather than to the tolerance ceiling the placement held.
        qtyWeighed.forEach((lineId, qty) ->
            reservations.trueUp(tenant.getId(), order.getId(), lineId, qty));

        PriceQuote issued = quote.quote();
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.PRICE_CONFIRMATION_PENDING)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_WEIGH")
            .note(note)
            .requestId(requestId)
            .auditAction("WEIGHED")
            .reasonCode(note)
            .outboxType("PRICE_CONFIRMATION_REQUESTED")
            .extras(Map.of("quote_id", issued.getId(),
                "quote_hash", issued.getQuoteHash(),
                "quoted_total_paise", issued.getTotalPaise()))
            .auditBefore(Map.of("status", OrderStatus.MERCHANT_ACCEPTED.name(),
                "total_paise", order.getTotalPaise()))
            .auditAfter(Map.of("status", OrderStatus.PRICE_CONFIRMATION_PENDING.name(),
                "quote_id", issued.getId(), "quoted_total_paise", issued.getTotalPaise()))
            .build());

        return withQuote(order, quote, ward);
    }

    /**
     * §6.2: the delivery decision, changed while it is still cheap to change. The gate is the lock,
     * not a status list the client can talk its way around — before it, the mode is a choice; after
     * it, the money may already be in somebody's pocket and the only way to change the mode is to
     * end the order and place a new one.
     *
     * <p>The decision and its fee are applied apart on purpose. Who holds the customer's cash follows
     * the mode at once, and before the lock no cash exists to move; the <em>total</em> follows only
     * when nobody needs to agree to it. A higher fee is the customer's to accept (§8.3), so it is
     * charged at T8 and not one moment before, and the quote that carries it stays ACTIVE as the
     * request they are answering. A lower or equal fee needs no consent (§8.2) and is applied now.
     */
    @Transactional
    public OrderViews.WithQuote reselectFulfilmentMode(AuthenticatedPrincipal caller, String code,
                                                      ModeBody body, String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_FULFILMENT_RESELECT", code);
        FulfilmentMode mode = parseMode(body == null ? null : body.fulfilmentMode());
        String reason = trimmed(body == null ? null : body.reason());
        if (reason == null) {
            throw fieldError("reason", "is required — say why the delivery choice changed");
        }
        if (order.isTerminal()) {
            throw illegal(order, "This order is over.");
        }
        if (order.getStatus() == OrderStatus.PRICE_CONFIRMATION_PENDING) {
            throw illegal(order, "The customer has not answered the weighed total yet.");
        }
        if (!PRE_LOCK.contains(order.getStatus())) {
            throw new ApiException(ErrorCode.FULFILMENT_LOCKED,
                "This order's delivery choice is locked. Cancel it and place a new order.",
                Map.of("status", order.getStatus().name(),
                    "fulfilment_mode_snapshot", order.getFulfilmentModeSnapshot()));
        }
        if (mode.name().equals(order.getFulfilmentModeSnapshot())) {
            throw fieldError("fulfilment_mode", "is the delivery choice this order already has");
        }
        Merchant shop = directory.shopInTenant(order.getTenantId(), order.getMerchantId());
        boolean allowed = mode == FulfilmentMode.MERCHANT
            ? shop.deliversItself() : shop.platformDelivers();
        if (!allowed) {
            throw new ApiException(ErrorCode.MODE_NOT_AVAILABLE,
                mode == FulfilmentMode.MERCHANT
                    ? "This shop does not deliver on its own."
                    : "This shop is not delivered by the platform.",
                Map.of("fulfilment_capability", shop.getFulfilmentCapability().name()));
        }

        Tenant tenant = serviceability.tenantById(order.getTenantId());
        ServiceWard ward = serviceability.wardById(tenant, order.getWardId());
        List<OrderLine> rows = lines.findByOrderIdOrderByIdAsc(order.getId());
        // A ward with no rule for the mode refuses here, before anything is written (MODE_NOT_AVAILABLE).
        OrderPricing.Repriced repriced = pricing.modeChange(tenant, order, ward, rows, mode,
            clock.instant());
        boolean rises = repriced.risesAgainst(order);
        QuoteSnapshot quote = quotes.issue(repriced.input());

        Map<String, Object> before = modeSnapshot(order);
        order.setFulfilmentModeSnapshot(mode.name());
        order.setCollectionChannel(CollectionChannel.forMode(mode));
        if (!rises) {
            order.applyMoney(order.getGoodsPaise(), repriced.deliveryFeePaise(),
                repriced.handlingFeePaise(), repriced.input().commissionPaise());
        }
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(order.getStatus())
            .kind(OrderEventKind.FULFILMENT_PROGRESS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_FULFILMENT_RESELECT")
            .note(reason)
            .requestId(requestId)
            .auditAction("FULFILMENT_MODE_CHANGED")
            .reasonCode(reason)
            .auditBefore(before)
            .auditAfter(modeSnapshot(order))
            .build());

        return withQuote(order, quote, ward);
    }

    /** T11: platform delivery only — a shop that drives itself has nothing to hand over. */
    @Transactional
    public OrderViews.Detail markReady(AuthenticatedPrincipal caller, String code,
                                      String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_MARK_READY", code);
        if (order.getStatus() != OrderStatus.PREPARING) {
            throw illegal(order, "This order is not being prepared.");
        }
        requireMode(order, "PLATFORM", "handed to a rider");
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.READY_FOR_PICKUP)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_MARK_READY")
            .requestId(requestId)
            .build());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /**
     * T12 and T18b: the shop sends its own rider. A second dispatch from {@code DELIVERY_FAILED} is
     * the same action and the same edge, which is why the gate takes two statuses.
     *
     * <p>No job, no rider and no custody row can ever exist for this order (§6.3): the mode was
     * checked, and the accrual module reads the snapshot rather than the presence of a job.
     */
    @Transactional
    public OrderViews.Detail dispatchSelf(AuthenticatedPrincipal caller, String code,
                                          String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_DISPATCH_SELF", code);
        if (!EnumSet.of(OrderStatus.PREPARING, OrderStatus.DELIVERY_FAILED)
            .contains(order.getStatus())) {
            throw illegal(order, "This order is not ready to be sent out.");
        }
        requireMode(order, "MERCHANT", "dispatched by the shop");
        requireNoConsentOutstanding(order);
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.OUT_FOR_DELIVERY)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_DISPATCH_SELF")
            .requestId(requestId)
            .build());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /** T17: the shop's own attempt failed. Retry is T18b, ending it is the cancel below. */
    @Transactional
    public OrderViews.Detail failSelfDelivery(AuthenticatedPrincipal caller, String code,
                                              String reason, String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_FAIL_SELF", code);
        if (order.getStatus() != OrderStatus.OUT_FOR_DELIVERY) {
            throw illegal(order, "This order is not out for delivery.");
        }
        FailReason why = FailReason.parse(reason);
        order.setFailReason(why.name());
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.DELIVERY_FAILED)
            .kind(OrderEventKind.STATUS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_FAIL_SELF")
            .note(why.name())
            .requestId(requestId)
            .reasonCode(why.name())
            .extras(Map.of("fail_reason", why.name()))
            .auditAfter(Map.of("status", OrderStatus.DELIVERY_FAILED.name(), "fail_reason",
                why.name()))
            .build());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /**
     * T10 and T9b: the shop ends an order it already accepted. The reason is mandatory — an order
     * that vanishes after acceptance is the event the customer calls about, and PRD §14 counts it.
     *
     * <p>{@code PRICE_CONFIRMATION_PENDING} is in the allowed set for T9b's case: the scales said
     * 1.9 kg against an estimate of 1 kg, and the shop's exit is a cancel with a reason rather than a
     * second weigh the transition table does not offer.
     */
    @Transactional
    public OrderViews.Detail cancel(AuthenticatedPrincipal caller, String code, String reason,
                                    String requestId) {
        Order order = scope.shopOrder(caller, "ORDER_CANCEL_AFTER_ACCEPT", code);
        if (!EnumSet.of(OrderStatus.MERCHANT_ACCEPTED, OrderStatus.PRICE_CONFIRMATION_PENDING,
            OrderStatus.PREPARING).contains(order.getStatus())) {
            throw illegal(order, "This order can no longer be cancelled by the shop.");
        }
        RejectReason why = RejectReason.parse(reason);
        order.setCancelReason(why.name());
        order.setCancelledAt(clock.instant());
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.CANCELLED)
            .kind(OrderEventKind.CANCELLATION)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_CANCEL_AFTER_ACCEPT")
            .note(why.name())
            .requestId(requestId)
            .outboxType("ORDER_CANCELLED_BY_SHOP")
            .reasonCode(why.name())
            .extras(Map.of("cancel_reason", why.name()))
            .auditAfter(Map.of("status", OrderStatus.CANCELLED.name(), "cancel_reason", why.name()))
            .build());
        reservations.releaseAll(order.getTenantId(), order.getId());
        return reads.detailOf(order, OrderViews.Viewer.SHOP);
    }

    /**
     * §6.2: an order does not move forward while a consent request waits, whatever its status says.
     * {@code PRICE_CONFIRMATION_REQUIRED} is the code §10.5.6 names for it, carrying the quote so the
     * shop's screen can show the customer the total they have not answered yet.
     */
    private void requireNoConsentOutstanding(Order order) {
        quotes.awaitingOrderQuote(order.getId()).ifPresent(snapshot -> {
            PriceQuote quote = snapshot.quote();
            throw new ApiException(ErrorCode.PRICE_CONFIRMATION_REQUIRED,
                "The customer has not answered the total on this order yet.",
                Map.of("quote_id", quote.getId(), "quote_hash", quote.getQuoteHash()));
        });
    }

    private static void requireMode(Order order, String wanted, String what) {
        if (!wanted.equals(order.getFulfilmentModeSnapshot())) {
            throw new ApiException(ErrorCode.MODE_FORBIDS_TRANSITION,
                "This order is " + order.getFulfilmentModeSnapshot() + " delivery; it is not "
                    + what + ".",
                Map.of("fulfilment_mode_snapshot", order.getFulfilmentModeSnapshot()));
        }
    }

    private ApiException illegal(Order order, String message) {
        boolean waiting = quotes.awaitingOrderQuote(order.getId()).isPresent();
        return new ApiException(ErrorCode.INVALID_TRANSITION, message,
            Map.of("status", order.getStatus().name(),
                "allowed_actions", views.allowedActions(order, OrderViews.Viewer.SHOP, waiting)));
    }

    /**
     * The weighed quantities, keyed by order line. Two refusals and one arithmetic guard: a line the
     * order does not have (or does not sell by weight) is a body that cannot be priced, a by-weight
     * line left out is a partial weigh, and a quantity coarser than {@code numeric(10,3)} would be
     * rounded by the database rather than by the money rules that own rounding (§8.4).
     */
    private static Map<String, BigDecimal> weighedQuantities(WeighBody body,
                                                            List<OrderLine> byWeight,
                                                            List<OrderLine> rows) {
        if (body.lines() == null || body.lines().isEmpty()) {
            throw fieldError("lines", "is required");
        }
        Map<String, OrderLine> weighable = new HashMap<>();
        byWeight.forEach(row -> weighable.put(row.getId(), row));
        Map<String, OrderLine> everyLine = new HashMap<>();
        rows.forEach(row -> everyLine.put(row.getId(), row));

        Map<String, BigDecimal> quantities = new HashMap<>();
        for (WeighBody.Line line : body.lines()) {
            String id = trimmed(line.orderLineId());
            if (id == null || !everyLine.containsKey(id)) {
                throw fieldError("lines.order_line_id", "names a line this order does not have");
            }
            if (!weighable.containsKey(id)) {
                throw fieldError("lines.order_line_id",
                    "names a line that is not sold by weight: " + everyLine.get(id).getProductCode());
            }
            BigDecimal qty = requireQuantity(line.qtyWeighed(), id);
            if (quantities.put(id, qty) != null) {
                throw fieldError("lines.order_line_id", "is repeated: " + id);
            }
        }
        List<String> missing = byWeight.stream()
            .filter(row -> !quantities.containsKey(row.getId()))
            .map(OrderLine::getProductCode)
            .toList();
        if (!missing.isEmpty()) {
            throw fieldError("lines", "must weigh every line sold by weight: " + missing);
        }
        return quantities;
    }

    private static BigDecimal requireQuantity(BigDecimal qty, String lineId) {
        if (qty == null || qty.signum() <= 0) {
            throw fieldError("lines.qty_weighed", "must be greater than zero for " + lineId);
        }
        if (qty.stripTrailingZeros().scale() > 3 || qty.precision() - qty.scale() > 7) {
            throw fieldError("lines.qty_weighed",
                "must have at most 3 decimals and 7 whole digits for " + lineId);
        }
        return qty;
    }

    /** §10.6.3's 422, with both numbers and the ceiling so the shop can see how far over it went. */
    private static ApiException outOfTolerance(OrderLine line, BigDecimal qty, int basisPoints) {
        BigDecimal ceiling = WeightTolerance.ceilingFor(line.getQtyOrdered(), basisPoints);
        return new ApiException(ErrorCode.WEIGHT_OUT_OF_TOLERANCE,
            "That weight is more than this order reserved. Cancel it with a reason if the shop "
                + "cannot supply the estimate.",
            Map.of("field_errors", List.of(Map.of("field", "lines.qty_weighed",
                "message", "is over the tolerance reserved for this line")),
                "order_line_id", line.getId(),
                "product_code", line.getProductCode(),
                "qty_ordered", line.getQtyOrdered(),
                "qty_weighed", qty,
                "max_qty_weighed", ceiling,
                "weight_tolerance_bp", basisPoints));
    }

    /** What §13.3's field allowlist expects on a mode change: the pair, and the money with it. */
    private static Map<String, Object> modeSnapshot(Order order) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", order.getStatus().name());
        snapshot.put("fulfilment_mode_snapshot", order.getFulfilmentModeSnapshot());
        snapshot.put("collection_channel", order.getCollectionChannel().name());
        snapshot.put("delivery_fee_paise", order.getDeliveryFeePaise());
        snapshot.put("total_paise", order.getTotalPaise());
        return snapshot;
    }

    /** §10.5.6's {@code {order, quote}}: the order as the shop sees it, and the quote just issued. */
    private OrderViews.WithQuote withQuote(Order order, QuoteSnapshot quote, ServiceWard ward) {
        return new OrderViews.WithQuote(reads.detailOf(order, OrderViews.Viewer.SHOP),
            quotes.render(quote, new QuoteViews.Ward(ward.getWardNo(), ward.getName())));
    }

    private static FulfilmentMode parseMode(String value) {
        String wanted = trimmed(value);
        if (wanted == null) {
            throw fieldError("fulfilment_mode", "is required");
        }
        try {
            return FulfilmentMode.valueOf(wanted.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw fieldError("fulfilment_mode", "must be PLATFORM or MERCHANT");
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }

    /** §10.6.3's body. {@code unit} is the label the client already displayed and changes nothing. */
    public record WeighBody(List<Line> lines, String note) {

        public record Line(String orderLineId, BigDecimal qtyWeighed, String unit) {
        }
    }

    /** §6.2's body: the mode and the reason the shop or the operator is giving. */
    public record ModeBody(String fulfilmentMode, String reason) {
    }
}
