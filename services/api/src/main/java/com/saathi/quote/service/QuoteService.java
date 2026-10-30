package com.saathi.quote.service;

import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import com.saathi.quote.domain.QuoteState;
import com.saathi.quote.repository.PriceQuoteLineRepository;
import com.saathi.quote.repository.PriceQuoteRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            .expiresAt(input.issuedAt().plusSeconds(Math.max(1, input.ttlMinutes()) * 60L))
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
     * A cart edit makes the live quote describe a cart that no longer exists. Superseding here
     * rather than deleting is §13.1: the customer's artifact stays answerable, it just stops being
     * the thing a placement may accept.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void supersedeCart(String cartId) {
        quotes.supersedeActiveCart(cartId, QuoteState.ACTIVE, QuoteState.SUPERSEDED);
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
     * between the check and the write.
     */
    private List<QuoteViews.StoredConsent> storedConsents(QuoteInput input,
                                                          Map<String, String> lineIdByProduct) {
        List<QuoteViews.StoredConsent> stored = new ArrayList<>();
        for (QuoteInput.Consent consent : input.consents()) {
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
