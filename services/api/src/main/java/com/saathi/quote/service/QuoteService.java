package com.saathi.quote.service;

import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import com.saathi.quote.domain.QuoteScope;
import com.saathi.quote.domain.QuoteState;
import com.saathi.quote.repository.PriceQuoteLineRepository;
import com.saathi.quote.repository.PriceQuoteRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * §8.1's quote lifecycle, and the only code that writes {@code price_quote}.
 *
 * <p>A new quote always supersedes the live one for the same cart or order <em>in the same
 * transaction</em>: the partial unique index allows exactly one {@code ACTIVE} row, so "supersede
 * then insert" is not an optimisation but the shape the schema demands. History is kept —
 * superseded and expired quotes are what makes "what was the customer shown" answerable (§13.1).
 *
 * <p>Expiry is never scheduled (§6.5). {@link #read} notices a stale quote when somebody looks at
 * it and records {@code EXPIRED_BY_RULE} before refusing, which is the only expiry the design
 * allows.
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final PriceQuoteRepository quotes;
    private final PriceQuoteLineRepository lines;
    private final QuoteViews views;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Issue a quote, superseding whatever was live for the same parent. MANDATORY: a quote written
     * outside the caller's transaction could survive a cart edit that rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public QuoteSnapshot issue(QuoteInput input) {
        if (input.lines() == null || input.lines().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A quote needs at least one line.");
        }
        supersedeLive(input.cartId(), input.orderId());

        String quoteId = Ulid.next(clock);
        List<PriceQuoteLine> rows = new ArrayList<>(input.lines().size());
        long goodsPaise = 0;
        List<QuoteHash.Line> hashLines = new ArrayList<>(input.lines().size());
        Map<String, String> lineIdByProduct = new HashMap<>();
        for (QuoteInput.Line line : input.lines()) {
            String lineId = Ulid.next(clock);
            long linePaise = QuoteViews.linePaise(line.chargedQty(), line.unitPricePaise());
            goodsPaise = Math.addExact(goodsPaise, linePaise);
            PriceQuoteLine row = PriceQuoteLine.builder()
                .id(lineId)
                .tenantId(input.tenantId())
                .quoteId(quoteId)
                .productId(line.productId())
                .productCode(line.productCode())
                .nameSnapshot(line.name())
                .unitLabelSnapshot(line.unitLabel())
                .qtyOrdered(line.qtyOrdered())
                .qtyWeighed(line.qtyWeighed())
                .unitPricePaise(line.unitPricePaise())
                .linePaise(linePaise)
                .priceDeltaPaise(line.priceDeltaPaise())
                .consentRequired(line.consentRequired())
                .build();
            rows.add(row);
            hashLines.add(new QuoteHash.Line(line.productId(), line.chargedQty(),
                line.unitPricePaise(), linePaise));
            lineIdByProduct.put(line.productId(), lineId);
        }

        long totalPaise = Math.addExact(goodsPaise,
            Math.addExact(input.deliveryFeePaise(), input.handlingFeePaise()));
        String hash = QuoteHash.compute(input.issuedAt(), input.mode(), goodsPaise,
            input.deliveryFeePaise(), input.handlingFeePaise(), totalPaise, hashLines);

        PriceQuote quote = PriceQuote.builder()
            .id(quoteId)
            .tenantId(input.tenantId())
            .scope(input.scope())
            .cartId(input.cartId())
            .orderId(input.orderId())
            .quoteHash(hash)
            .goodsPaise(goodsPaise)
            .deliveryFeePaise(input.deliveryFeePaise())
            .handlingFeePaise(input.handlingFeePaise())
            .totalPaise(totalPaise)
            .commissionPaise(input.commissionPaise())
            .consentRequirementsJson(json.writeValueAsString(storedConsents(input, lineIdByProduct)))
            .fulfilmentOptionsJson(json.writeValueAsString(storedOptions(input.options())))
            .fulfilmentModeSnapshot(input.mode())
            .state(QuoteState.ACTIVE)
            .issuedAt(input.issuedAt())
            // A zero-minute TTL would make the quote stale before the response was written; the
            // tenant's value is a policy about freshness, not a way to create unusable quotes.
            // A null TTL is a different promise: this quote does not expire (§6.5).
            .expiresAt(expiryOf(input.issuedAt(), input.ttlMinutes()))
            .build();
        // Flush before the lines: price_quote_line has a foreign key to the quote, and Hibernate
        // sorts inserts by entity type rather than by dependency.
        quotes.saveAndFlush(quote);
        lines.saveAll(rows);
        return new QuoteSnapshot(quote, List.copyOf(rows));
    }

    /** §8.1's {@code ACCEPTED}, recording who consented and when. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PriceQuote accept(PriceQuote quote, String acceptedBy) {
        if (quote.getState() != QuoteState.ACTIVE) {
            throw new ApiException(ErrorCode.QUOTE_STALE, "That quote is no longer active.");
        }
        quote.setState(QuoteState.ACCEPTED);
        quote.setAcceptedAt(clock.instant());
        quote.setAcceptedBy(acceptedBy);
        return quotes.save(quote);
    }

    /** The quote a cart is currently showing, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<QuoteSnapshot> activeCartQuote(String cartId) {
        return quotes.findFirstByCartIdAndStateOrderByIssuedAtDesc(cartId, QuoteState.ACTIVE)
            .map(this::snapshot);
    }

    /**
     * The consent request an order is waiting on: the weight quote after T7, or the fulfilment
     * change a shop raised under §6.2. There is at most one because {@code price_quote_active_order}
     * is a partial unique index, which is also why {@code POST /orders/{code}/price-confirmation}
     * can say {@code QUOTE_STALE} — a second weigh made this order's active quote a different row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<QuoteSnapshot> activeOrderQuote(String orderId) {
        return quotes.findFirstByOrderIdAndStateOrderByIssuedAtDesc(orderId, QuoteState.ACTIVE)
            .map(this::snapshot);
    }

    /**
     * {@link #activeOrderQuote} restricted to the quotes that are still a question. This is what
     * §10.4's {@code active_quote} is for, and the difference matters exactly once: a §6.2 requote
     * that did <em>not</em> raise the total is an {@code ACTIVE} row nobody is being asked to answer,
     * and rendering it as a pending decision would offer a customer a button for a figure they have
     * already been charged.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<QuoteSnapshot> awaitingOrderQuote(String orderId) {
        return activeOrderQuote(orderId).filter(snapshot -> asksTheCustomer(snapshot.quote()));
    }

    /**
     * Whether this quote still needs the customer to decide (§8.3).
     *
     * <p>A {@code WEIGHT} quote always does: §6.3's T7 moves the order to
     * {@code PRICE_CONFIRMATION_PENDING} whatever the scales produced, because D6's promise is that
     * the customer pays the weighed figure <em>having seen it</em> — a lower total is charged, not
     * silently granted. A {@code FULFILMENT_CHANGE} quote asks only through its consent list, which
     * {@code OrderPricing} populates only when the new fee raises the total; §8.2's rule for a
     * decrease is that no consent is needed and the lower figure applies.
     */
    public boolean asksTheCustomer(PriceQuote quote) {
        return quote.getScope() == QuoteScope.WEIGHT || !views.readConsents(
            quote.getConsentRequirementsJson()).isEmpty();
    }

    /**
     * The orders in this page that are waiting on a consent answer, in one query. A list cannot ask
     * {@link #awaitingOrderQuote} per row: that is a round trip per order for a boolean the client
     * only needs to decide whether {@code allowed_actions} says {@code price_confirmation}.
     */
    @Transactional(readOnly = true)
    public Set<String> pendingConsentOrderIds(Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return Set.of();
        }
        return quotes.findByOrderIdInAndState(orderIds, QuoteState.ACTIVE).stream()
            .filter(this::asksTheCustomer)
            .map(PriceQuote::getOrderId)
            .collect(Collectors.toSet());
    }

    /**
     * A cart edit makes the live quote describe a cart that no longer exists. Superseding here
     * rather than deleting is §13.1: the customer's artifact stays answerable, it just stops being
     * the thing a placement may accept.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void supersedeCart(String cartId) {
        quotes.supersedeActiveCart(cartId, QuoteState.ACTIVE, QuoteState.SUPERSEDED);
    }

    /**
     * Close whatever consent request an order is holding, for a caller that is ending the order
     * rather than re-pricing it (§6.3's T9 "quote {@code SUPERSEDED}").
     *
     * <p>This reads the row instead of running the bulk update {@link #issue} uses, because a caller
     * that already has the quote as a managed entity — the customer declining the very total on it —
     * must not have that instance flushed back over the update a moment later.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void supersedeOrder(String orderId) {
        quotes.findFirstByOrderIdAndStateOrderByIssuedAtDesc(orderId, QuoteState.ACTIVE)
            .ifPresent(quote -> {
                quote.setState(QuoteState.SUPERSEDED);
                quotes.save(quote);
            });
    }

    /** A quote looked up without the read-triggered expiry, for a caller about to re-validate it. */
    @Transactional(readOnly = true)
    public Optional<QuoteSnapshot> find(String tenantId, String quoteId) {
        return quotes.findByIdAndTenantId(quoteId, tenantId).map(this::snapshot);
    }

    /** Record {@code EXPIRED_BY_RULE} inside the caller's transaction (§8.2, §6.5). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(PriceQuote quote) {
        if (quote.getState() == QuoteState.ACTIVE) {
            quote.setState(QuoteState.EXPIRED_BY_RULE);
            quotes.save(quote);
        }
    }

    /**
     * A quote as its owner reads it. The transaction is what makes lazy expiry observable: {@code
     * EXPIRED_BY_RULE} is written and then {@code QUOTE_STALE} is thrown, and {@code noRollbackFor}
     * keeps the write while the refusal leaves. A nested {@code REQUIRES_NEW} cannot do this from
     * here — a self-invocation never crosses the proxy — so the rule lives on this boundary.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public QuoteSnapshot read(String tenantId, String quoteId) {
        PriceQuote quote = quotes.findByIdAndTenantId(quoteId, tenantId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote."));
        if (quote.getState() == QuoteState.ACTIVE && quote.isExpiredAt(clock.instant())) {
            expireAndRefuse(tenantId, quoteId);
        }
        return snapshot(quote);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public QuoteSnapshot snapshot(PriceQuote quote) {
        return new QuoteSnapshot(quote, lines.findByQuoteIdOrderByIdAsc(quote.getId()));
    }

    public QuoteViews.QuoteView render(QuoteSnapshot snapshot, QuoteViews.Ward ward) {
        return views.of(snapshot, ward);
    }

    /**
     * §8.2's lazy expiry. Runs inside the caller's transaction, whose {@code noRollbackFor} is what
     * makes the recorded state survive the {@code QUOTE_STALE} that follows it.
     */
    private void expireAndRefuse(String tenantId, String quoteId) {
        PriceQuote quote = quotes.findByIdAndTenantId(quoteId, tenantId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote."));
        if (quote.getState() == QuoteState.ACTIVE) {
            quote.setState(QuoteState.EXPIRED_BY_RULE);
            quotes.saveAndFlush(quote);
        }
        throw new ApiException(ErrorCode.QUOTE_STALE,
            "That quote is out of date. Refresh the total and try again.");
    }

    /**
     * The total independent of who computed it, so a re-validation and a fresh issue cannot
     * disagree about goods + fees.
     */
    public static long totalOf(long goodsPaise, long deliveryFeePaise, long handlingFeePaise) {
        return Math.addExact(goodsPaise, Math.addExact(deliveryFeePaise, handlingFeePaise));
    }

    private static Instant expiryOf(Instant issuedAt, Integer ttlMinutes) {
        return ttlMinutes == null ? null : issuedAt.plusSeconds(Math.max(1, ttlMinutes) * 60L);
    }

    private void supersedeLive(String cartId, String orderId) {
        if (cartId != null) {
            quotes.supersedeActiveCart(cartId, QuoteState.ACTIVE, QuoteState.SUPERSEDED);
        }
        if (orderId != null) {
            quotes.supersedeActiveOrder(orderId, QuoteState.ACTIVE, QuoteState.SUPERSEDED);
        }
    }

    /**
     * The stored consent list names the quote's own line, not the product: the customer consents
     * to a line of an artifact, and a product id would stop meaning anything if the cart changed
     * between the check and the write. An order-level requirement (§6.2's fulfilment change) names
     * no product and stores with a null line.
     */
    private List<QuoteViews.StoredConsent> storedConsents(QuoteInput input,
                                                          Map<String, String> lineIdByProduct) {
        List<QuoteViews.StoredConsent> stored = new ArrayList<>();
        for (QuoteInput.Consent consent : input.consents()) {
            if (consent.productId() == null) {
                stored.add(new QuoteViews.StoredConsent(consent.code(), null,
                    consent.fromPaise(), consent.toPaise()));
                continue;
            }
            String lineId = lineIdByProduct.get(consent.productId());
            if (lineId == null) {
                throw new IllegalStateException(
                    "consent names a product that is not in the quote: " + consent.productId());
            }
            stored.add(new QuoteViews.StoredConsent(consent.code(), lineId,
                consent.fromPaise(), consent.toPaise()));
        }
        return stored;
    }

    private static List<QuoteViews.StoredOption> storedOptions(List<QuoteInput.Option> options) {
        return options.stream()
            .map(option -> new QuoteViews.StoredOption(option.mode(), option.totalPaise(),
                option.etaNote()))
            .toList();
    }
}
