package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderEventKind;
import com.saathi.order.domain.OrderLine;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.domain.PriceSource;
import com.saathi.order.repository.OrderLineRepository;
import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import com.saathi.quote.domain.QuoteScope;
import com.saathi.quote.service.QuoteHash;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteSnapshot;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.stock.service.ReservationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.6.2: the customer's answer to a total the shop changed — T8 accept, T9 decline.
 *
 * <p>One endpoint for both halves of §6.3's two edges and for both quote scopes, because the customer
 * is answering the same question either way: "this is the figure, do you accept it". The scope decides
 * what the accept writes, and the document is explicit that only one of those things is a price move —
 * §6.3 calls T8 "the only edge that rewrites order totals", and this is the only code that does it.
 *
 * <p>The pair {@code (quote_id, quote_hash)} is the consent. The id says which artifact the customer
 * was looking at and the hash says which numbers were on it, so a second weigh that re-issued the same
 * order's quote cannot be accepted by an answer written against the first: it is {@code QUOTE_STALE},
 * and the client re-renders. No expiry is involved in that refusal — a weighed total does not expire
 * (§6.5), which is why V7 made {@code expires_at} nullable.
 */
@Service
@RequiredArgsConstructor
public class OrderConsent {

    /** §10.6.2's T9: the reason the order carries when the customer turns the weighed total down. */
    private static final String PRICE_DECLINED = "PRICE_DECLINED";

    private final OrderScope scope;
    private final OrderLineRepository lines;
    private final OrderQueryService reads;
    private final OrderViews views;
    private final OrderTrail trail;
    private final QuoteService quotes;
    private final ReservationService reservations;
    private final Clock clock;

    /** What the controller sends. Both halves of the pair are mandatory (§10.6.2). */
    public record Body(String decision, String quoteId, String quoteHash) {
    }

    @Transactional
    public OrderViews.Detail answer(AuthenticatedPrincipal caller, String code, Body body,
                                    String requestId) {
        boolean accept = parseDecision(body);
        Order order = scope.customerOrder(caller,
            accept ? "ORDER_CONFIRM_PRICE" : "ORDER_DECLINE_PRICE", code);
        PriceQuote quote = requireAnswerable(order, body, accept);

        return accept ? accept(caller, order, quote, requestId)
            : decline(caller, order, quote, requestId);
    }

    /**
     * T8. The weighed lines become the order's, the totals follow the quote, and the order resumes:
     * straight to {@code PREPARING} when the shop had stopped to weigh (§6.3's T8 target), or nowhere
     * at all when the answer was about a delivery-mode fee, which never moved the status.
     */
    private OrderViews.Detail accept(AuthenticatedPrincipal caller, Order order, PriceQuote quote,
                                     String requestId) {
        quotes.accept(quote, caller.accountId());
        Map<String, Object> before = moneyOf(order);
        if (quote.getScope() == QuoteScope.WEIGHT) {
            applyWeighing(order, quote);
        }
        order.applyMoney(quote.getGoodsPaise(), quote.getDeliveryFeePaise(),
            quote.getHandlingFeePaise(), quote.getCommissionPaise());

        boolean resumed = order.getStatus() == OrderStatus.PRICE_CONFIRMATION_PENDING;
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(resumed ? OrderStatus.PREPARING : order.getStatus())
            // A consent that moves nothing is the §6.2 case: the shop changed the mode, the customer
            // agreed to the fee, and the order stays where it was for the shop to prepare. The schema
            // reserves kind=CONSENT for a status move (order_event_self_edge), so the milestone name
            // carries it instead.
            .kind(resumed ? OrderEventKind.CONSENT : OrderEventKind.FULFILMENT_PROGRESS)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_CONFIRM_PRICE")
            .requestId(requestId)
            .auditAction("QUOTE_ACCEPTED")
            .auditBefore(before)
            .auditAfter(moneyOf(order))
            .build());
        return reads.detailOf(order, OrderViews.Viewer.CUSTOMER);
    }

    /**
     * T9: the customer turns the new total down. Nothing is refunded because nothing was collected —
     * §6.3 says so out loud because the temptation to write a refund here is a bug waiting to make the
     * ledger disagree (§9.7's I-6).
     */
    private OrderViews.Detail decline(AuthenticatedPrincipal caller, Order order, PriceQuote quote,
                                      String requestId) {
        Map<String, Object> before = moneyOf(order);
        order.setCancelReason(PRICE_DECLINED);
        order.setCancelledAt(clock.instant());
        trail.record(OrderTrail.Edge.builder()
            .order(order)
            .to(OrderStatus.CANCELLED)
            .kind(OrderEventKind.CANCELLATION)
            .actor(caller.kind())
            .actorId(caller.accountId())
            .permission("ORDER_DECLINE_PRICE")
            .note(PRICE_DECLINED)
            .requestId(requestId)
            .auditAction("QUOTE_DECLINED")
            // QUOTE_DECLINED is one of §13.2's ⚑ actions, and the decline carries no free text: the
            // customer declined a figure, which is the reason.
            .reasonCode(PRICE_DECLINED)
            .outboxType("ORDER_CANCELLED")
            .extras(Map.of("cancel_reason", PRICE_DECLINED,
                "quote_id", quote.getId(),
                "declined_total_paise", quote.getTotalPaise()))
            .auditBefore(before)
            .auditAfter(Map.of("status", OrderStatus.CANCELLED.name(),
                "cancel_reason", PRICE_DECLINED, "quote_id", quote.getId()))
            .build());
        // The edge already superseded the quote: CANCELLED is terminal, and a live consent request on
        // an order that is over is a total somebody could still accept.
        reservations.releaseAll(order.getTenantId(), order.getId());
        return reads.detailOf(order, OrderViews.Viewer.CUSTOMER);
    }

    /**
     * §6.3's T8 writes on a weighed order: the quantity the scales produced, the price that follows
     * from it, and the provenance that says this line is no longer an estimate. All three are read off
     * the quote rather than recomputed, because the customer consented to the quote's arithmetic and
     * the order must end up describing exactly that (§8.1).
     */
    private void applyWeighing(Order order, PriceQuote quote) {
        Map<String, PriceQuoteLine> byProduct = new HashMap<>();
        for (PriceQuoteLine line : quotes.snapshot(quote).lines()) {
            byProduct.put(line.getProductId(), line);
        }
        List<OrderLine> rows = lines.findByOrderIdOrderByIdAsc(order.getId());
        Instant at = clock.instant();
        for (OrderLine row : rows) {
            PriceQuoteLine weighed = byProduct.get(row.getProductId());
            if (weighed == null || weighed.getQtyWeighed() == null) {
                continue;
            }
            row.setQtyWeighed(weighed.getQtyWeighed());
            row.setLinePaise(weighed.getLinePaise());
            row.setPriceSource(PriceSource.WEIGHED);
            row.setConsentedAt(at);
            lines.save(row);
        }
    }

    /**
     * The quote this answer is for, or the refusal that says it is not. Three separate questions have
     * three separate answers: is this order waiting on anybody at all, is it waiting on this customer,
     * and is the artifact they named still the live one.
     */
    private PriceQuote requireAnswerable(Order order, Body body, boolean accept) {
        QuoteSnapshot pending = quotes.awaitingOrderQuote(order.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TRANSITION,
                "Nothing on this order is waiting for your answer.",
                Map.of("status", order.getStatus().name(), "allowed_actions",
                    views.allowedActions(order, OrderViews.Viewer.CUSTOMER, false))));
        PriceQuote quote = pending.quote();
        String id = body.quoteId() == null ? null : body.quoteId().trim();
        String hash = body.quoteHash() == null ? null : QuoteHash.bare(body.quoteHash());
        if (!quote.getId().equals(id) || quote.getQuoteHash() == null
            || !quote.getQuoteHash().equals(hash)) {
            throw new ApiException(ErrorCode.QUOTE_STALE,
                "That total is no longer the one on this order. Refresh it and answer again.",
                Map.of("quote_id", quote.getId(), "quote_hash", QuoteHash.display(
                    quote.getQuoteHash()), "decision", accept ? "ACCEPT" : "DECLINE"));
        }
        return quote;
    }

    private static boolean parseDecision(Body body) {
        String decision = body == null || body.decision() == null
            ? null : body.decision().trim().toUpperCase(Locale.ROOT);
        if ("ACCEPT".equals(decision)) {
            return true;
        }
        if ("DECLINE".equals(decision)) {
            return false;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", "decision",
                "message", "must be ACCEPT or DECLINE"))));
    }

    /** The money a consent moves, for the audit row that has to say what the answer cost. */
    private static Map<String, Object> moneyOf(Order order) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("status", order.getStatus().name());
        snapshot.put("goods_paise", order.getGoodsPaise());
        snapshot.put("delivery_fee_paise", order.getDeliveryFeePaise());
        snapshot.put("handling_fee_paise", order.getHandlingFeePaise());
        snapshot.put("total_paise", order.getTotalPaise());
        snapshot.put("outstanding_paise", order.getOutstandingPaise());
        return snapshot;
    }
}
