package com.saathi.order.service;

import com.saathi.cart.domain.Cart;
import com.saathi.cart.service.CartPricing;
import com.saathi.cart.service.CartService;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.order.domain.CollectionChannel;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEvent;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderLine;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.domain.PaymentMethod;
import com.saathi.order.domain.PaymentStatus;
import com.saathi.order.domain.PriceSource;
import com.saathi.order.domain.SettlementStatus;
import com.saathi.order.repository.IdempotencyStore;
import com.saathi.order.repository.OrderEventRepository;
import com.saathi.order.repository.OrderLineRepository;
import com.saathi.order.repository.OrderRepository;
import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import com.saathi.quote.service.QuoteHash;
import com.saathi.quote.service.QuoteInput;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteSnapshot;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.money.Money;
import com.saathi.shared.security.ContactCipher;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.stock.domain.StockPolicy;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.ServiceabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /orders} (§10.6.1): T1 + T2 in one transaction.
 *
 * <p>The transaction is the shape of the contract. It locks the products, re-reads the prices and
 * fees, compares the recomputed quote hash with the one the customer accepted, and only then writes
 * the order, its lines, its stock holds, its two events, the outbox row, the cleared cart and the
 * idempotency record. A failure anywhere rolls back all of it, which is what makes "the order
 * exists" mean "all of that happened".
 *
 * <p>Two outcomes deliberately do not throw: a price increase and an expired quote. Both change
 * persisted state (the old quote is superseded, a fresh one issued or the stale one expired) and
 * both must surface <em>after</em> that state commits, so the writer returns them and the facade
 * raises them once the transaction is over.
 */
@Service
@RequiredArgsConstructor
public class OrderWriter {

    public sealed interface Attempt {

        record Placed(String bodyJson, String orderCode) implements Attempt {
        }

        record Replayed(String bodyJson, String resourceId) implements Attempt {
        }

        record NeedsConsent(QuoteSnapshot quote, ServiceWard ward) implements Attempt {
        }

        record Expired(String message) implements Attempt {
        }
    }

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderEventRepository events;
    private final IdempotencyStore idempotency;
    private final CartService carts;
    private final CartPricing pricing;
    private final QuoteService quotes;
    private final ServiceabilityService serviceability;
    private final OrderStock stock;
    private final ContactCipher contactCipher;
    private final OrderTrail trail;
    private final OrderViews views;
    private final OrderContacts contacts;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public Attempt attempt(AuthenticatedPrincipal caller, String key, OrderIntake.Request request,
                           String requestHash, String requestId) {
        String endpoint = OrderIntake.ENDPOINT;
        Optional<IdempotencyStore.Record> seen = idempotency.find(caller.tenantId(), key, endpoint);
        if (seen.isPresent()) {
            return replayOf(seen.get(), requestHash);
        }

        Instant now = clock.instant();
        Tenant tenant = serviceability.liveTenantById(caller.tenantId());
        Cart cart = carts.cartFor(tenant.getId(), caller.accountId());
        QuoteSnapshot snapshot = quotes.find(tenant.getId(), request.quoteId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote."));
        PriceQuote quote = snapshot.quote();
        if (!cart.getId().equals(quote.getCartId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote.");
        }
        if (quote.getState() != com.saathi.quote.domain.QuoteState.ACTIVE) {
            return new Attempt.Expired("That quote is no longer active. Refresh the total.");
        }
        if (quote.isExpiredAt(now)) {
            quotes.expire(quote);
            return new Attempt.Expired("That quote is out of date. Refresh the total.");
        }
        if (!QuoteHash.bare(request.quoteHash()).equals(quote.getQuoteHash())) {
            return new Attempt.Expired("That total is not the one on your screen. Refresh it.");
        }

        ServiceWard ward = serviceability.wardById(tenant, cart.getWardId());
        if (request.wardNo() != null && request.wardNo() != ward.getWardNo()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "This total was calculated for another area. Update your area first.");
        }
        if (request.mode() != null
            && !request.mode().equals(quote.getFulfilmentModeSnapshot())) {
            throw new ApiException(ErrorCode.MODE_FORBIDS_TRANSITION,
                "This total was calculated for a different delivery choice.");
        }

        // The re-validation uses the quote's own issued_at: a fresh timestamp would make every
        // recomputation a different hash and turn every placement into a price change (§8.1).
        CartPricing.Priced priced = pricing.price(tenant, cart, ward,
            quote.getFulfilmentModeSnapshot(), quote.getIssuedAt());
        if (!sameLines(snapshot.lines(), priced.input().lines())) {
            return new Attempt.Expired("Your cart changed after that total. Refresh it.");
        }

        String recomputed = recomputedHash(priced, quote.getIssuedAt());
        boolean unchanged = recomputed.equals(quote.getQuoteHash());

        if (!unchanged && anyIncrease(snapshot.lines(), quote, priced)) {
            QuoteSnapshot fresh = quotes.issue(at(priced.input(), now));
            return new Attempt.NeedsConsent(fresh, ward);
        }

        if (!unchanged) {
            if (!claim(caller, key, requestHash, now)) {
                return replayOf(idempotency.find(caller.tenantId(), key, endpoint)
                    .orElseThrow(IllegalStateException::new), requestHash);
            }
            QuoteSnapshot fresh = quotes.issue(at(priced.input(), now));
            quotes.accept(fresh.quote(), caller.accountId());
            return place(caller, tenant, cart, ward, priced, fresh, request, key, requestId, now);
        }

        if (!claim(caller, key, requestHash, now)) {
            return replayOf(idempotency.find(caller.tenantId(), key, endpoint)
                .orElseThrow(IllegalStateException::new), requestHash);
        }
        quotes.accept(quote, caller.accountId());
        return place(caller, tenant, cart, ward, priced, snapshot, request, key, requestId, now);
    }

    private Attempt place(AuthenticatedPrincipal caller, Tenant tenant, Cart cart, ServiceWard ward,
                          CartPricing.Priced priced, QuoteSnapshot acceptedQuote,
                          OrderIntake.Request request, String key, String requestId, Instant now) {
        PriceQuote quote = acceptedQuote.quote();
        String orderId = Ulid.next(clock);
        Order order = Order.builder()
            .id(orderId)
            .code(nextCode())
            .tenantId(tenant.getId())
            .merchantId(priced.merchant().getId())
            .customerId(caller.accountId())
            .wardId(ward.getId())
            .status(OrderStatus.CREATED)
            .fulfilmentModeSnapshot(quote.getFulfilmentModeSnapshot())
            .fulfilmentLockedAt(now)
            .paymentMethod(PaymentMethod.COD)
            .collectionChannel(
                CollectionChannel.forMode(FulfilmentMode.valueOf(quote.getFulfilmentModeSnapshot())))
            .paymentStatus(PaymentStatus.UNPAID)
            .settlementStatus(SettlementStatus.NOT_DUE)
            .quoteId(quote.getId())
            .idempotencyKey(key)
            .placedAt(now)
            .goodsPaise(quote.getGoodsPaise())
            .deliveryFeePaise(quote.getDeliveryFeePaise())
            .handlingFeePaise(quote.getHandlingFeePaise())
            .totalPaise(quote.getTotalPaise())
            .commissionBpSnapshot(tenant.getDefaultCommissionBp())
            .commissionPaise(quote.getCommissionPaise())
            .collectedPaise(0)
            .outstandingPaise(quote.getTotalPaise())
            .deliveryNote(request.deliveryNote())
            .addressSnapshot(addressJson(request))
            .contactPhoneCipher(contactCipher.encrypt(request.phoneE164()))
            .attemptCount(0)
            .addressChangedCount(0)
            .build();
        orders.saveAndFlush(order);

        List<OrderLine> rows = new ArrayList<>(acceptedQuote.lines().size());
        for (PriceQuoteLine quoteLine : acceptedQuote.lines()) {
            rows.add(OrderLine.builder()
                .id(Ulid.next(clock))
                .tenantId(tenant.getId())
                .orderId(order.getId())
                .productId(quoteLine.getProductId())
                .productCode(quoteLine.getProductCode())
                .nameSnapshot(quoteLine.getNameSnapshot())
                .unitLabelSnapshot(quoteLine.getUnitLabelSnapshot())
                .qtyOrdered(quoteLine.getQtyOrdered())
                .qtyWeighed(quoteLine.getQtyWeighed())
                .unitPricePaise(quoteLine.getUnitPricePaise())
                .linePaise(quoteLine.getLinePaise())
                .priceSource(quoteLine.getQtyWeighed() == null
                    ? PriceSource.LIST : PriceSource.WEIGHED)
                .build());
        }
        lines.saveAllAndFlush(rows);

        // §7.2's tolerance hold: a by-weight line reserves the estimate plus the band, so the shop
        // can put a slightly bigger bag in the box without the platform discovering at weighing
        // (§7.2) that the goods were already promised to somebody else.
        stock.holdFor(StockPolicy.RESERVE_ON_PLACEMENT, tenant, priced.merchant().getId(), rows);

        // T1 is the row coming into existence rather than a status moving, so the placement writes
        // its own first event — §13.1 still wants the evidence row beside it.
        OrderEvent created = event(order, null, OrderStatus.CREATED, OrderEventKind.STATUS,
            caller.kind(), caller.accountId(), "ORDER_CREATE", now, requestId);
        events.saveAndFlush(created);
        trail.audit(order, caller.kind(), caller.accountId(), "ORDER_CREATED", null,
            Map.of("status", OrderStatus.CREATED.name()), null, requestId, now);

        // §6.3's T2 is a system edge inside the same transaction, and the order's first message.
        OrderEvent confirmed = trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.PENDING_CONFIRMATION)
            .kind(OrderEventKind.STATUS)
            .actor(PrincipalKind.SYSTEM)
            .requestId(requestId)
            .outboxType("ORDER_PLACED")
            .extras(Map.of("merchant_id", order.getMerchantId()))
            .build());

        carts.clearAfterOrder(tenant.getId(), cart.getId());

        OrderViews.Detail detail = views.detail(order, rows, List.of(created, confirmed),
            priced.merchant(), ward,
            OrderViews.Viewer.CUSTOMER, null,
            contacts.forViewer(order, OrderViews.Viewer.CUSTOMER));
        OrderViews.Placement placement = new OrderViews.Placement(detail,
            new OrderViews.AcceptedQuote(quote.getId(), quote.getState().name(),
                quote.getAcceptedAt()));
        String body = json.writeValueAsString(placement);
        idempotency.complete(tenant.getId(), key, OrderIntake.ENDPOINT, 201, body,
            order.getId(), now);
        return new Attempt.Placed(body, order.getCode());
    }

    private boolean claim(AuthenticatedPrincipal caller, String key, String requestHash,
                          Instant now) {
        return idempotency.claim(caller.tenantId(), key, OrderIntake.ENDPOINT, requestHash, now);
    }

    private Attempt replayOf(IdempotencyStore.Record record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.DUPLICATE_SUBMISSION,
                "That Idempotency-Key was used for a different order.");
        }
        if (record.responseStatus() == null) {
            throw new ApiException(ErrorCode.CONFLICT,
                "This order is still being placed. Try again in a moment.");
        }
        return new Attempt.Replayed(record.responseJson(), record.resourceId());
    }

    /**
     * §8.2's rule read literally: an increase anywhere — a line, the delivery fee, the handling
     * fee — needs consent, even when another line fell far enough to lower the total.
     */
    private static boolean anyIncrease(List<PriceQuoteLine> quoted, PriceQuote quote,
                                       CartPricing.Priced priced) {
        Map<String, Long> priceNow = new HashMap<>();
        for (QuoteInput.Line line : priced.input().lines()) {
            priceNow.put(line.productId(), line.unitPricePaise());
        }
        for (PriceQuoteLine line : quoted) {
            Long current = priceNow.get(line.getProductId());
            if (current != null && current > line.getUnitPricePaise()) {
                return true;
            }
        }
        return priced.deliveryFeePaise() > quote.getDeliveryFeePaise()
            || priced.handlingFeePaise() > quote.getHandlingFeePaise();
    }

    /** Same products in the same quantities; anything else means the cart moved under the quote. */
    private static boolean sameLines(List<PriceQuoteLine> quoted, List<QuoteInput.Line> current) {
        if (quoted.size() != current.size()) {
            return false;
        }
        Map<String, BigDecimal> byProduct = new HashMap<>();
        for (QuoteInput.Line line : current) {
            byProduct.put(line.productId(), line.chargedQty());
        }
        for (PriceQuoteLine line : quoted) {
            BigDecimal qty = byProduct.get(line.getProductId());
            BigDecimal quotedQty = line.getQtyWeighed() == null
                ? line.getQtyOrdered() : line.getQtyWeighed();
            if (qty == null || qty.compareTo(quotedQty) != 0) {
                return false;
            }
        }
        return true;
    }

    private static String recomputedHash(CartPricing.Priced priced, Instant issuedAt) {
        List<QuoteHash.Line> hashLines = priced.input().lines().stream()
            .map(line -> new QuoteHash.Line(line.productId(), line.chargedQty(),
                line.unitPricePaise(),
                Money.ofPaise(line.unitPricePaise()).multipliedBy(line.chargedQty()).paise()))
            .toList();
        return QuoteHash.compute(issuedAt, priced.mode(), priced.goodsPaise(),
            priced.deliveryFeePaise(), priced.handlingFeePaise(), priced.totalPaise(), hashLines);
    }

    private static QuoteInput at(QuoteInput input, Instant issuedAt) {
        return new QuoteInput(input.tenantId(), input.cartId(), input.orderId(), input.scope(),
            input.mode(), input.deliveryFeePaise(), input.handlingFeePaise(), input.commissionPaise(),
            input.ttlMinutes(), issuedAt, input.lines(), input.consents(), input.options());
    }

    private OrderEvent event(Order order, OrderStatus from, OrderStatus to, OrderEventKind kind,
                             PrincipalKind actor, String actorId, String permission, Instant at,
                             String requestId) {
        return OrderEvent.builder()
            .id(Ulid.next(clock))
            .tenantId(order.getTenantId())
            .orderId(order.getId())
            .fromStatus(from)
            .toStatus(to)
            .kind(kind)
            .actorType(actor)
            .actorId(actorId)
            .permissionUsed(permission)
            .occurredAt(at)
            .requestId(requestId)
            .build();
    }

    private String nextCode() {
        Long next = jdbc.queryForObject("SELECT nextval('order_code_seq')", Long.class);
        return "SAATH-" + Objects.requireNonNull(next);
    }

    private String addressJson(OrderIntake.Request request) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("line1", request.line1());
        address.put("landmark", request.landmark());
        address.put("pincode", request.pincode());
        return json.writeValueAsString(address);
    }
}
