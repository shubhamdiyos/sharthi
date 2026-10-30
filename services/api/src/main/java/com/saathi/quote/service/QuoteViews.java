package com.saathi.quote.service;

import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import com.saathi.shared.money.Money;
import com.saathi.shared.money.MoneyView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * §10.4's {@code QuoteView}. The wire shape is built here and nowhere else, so a re-rendered quote
 * and a freshly issued one cannot disagree about a field name or a rounding rule (§8.4).
 */
@Component
@RequiredArgsConstructor
public class QuoteViews {

    private final ObjectMapper json;

    public record QuoteView(Quote quote, List<Line> lines) {
    }

    /**
     * The city's own name for the ward, because a client names geography by the public pair and
     * never by an internal id (§4.3-6).
     */
    public record Ward(int wardNo, String name) {
    }

    public record Quote(
        String id,
        String scope,
        String state,
        String quoteHash,
        Instant issuedAt,
        Instant expiresAt,
        String fulfilmentModeSnapshot,
        Ward ward,
        Totals totals,
        List<Consent> consentRequired,
        List<Option> fulfilmentOptions) {
    }

    public record Totals(
        MoneyView goods,
        MoneyView deliveryFee,
        MoneyView handlingFee,
        MoneyView total,
        MoneyView commissionIllustrative) {
    }

    public record Consent(String code, String lineId, MoneyView from, MoneyView to) {
    }

    public record Option(String mode, MoneyView total, String etaNote) {
    }

    public record Line(
        String id,
        String productCode,
        String name,
        String unitLabel,
        BigDecimal qtyOrdered,
        BigDecimal qtyWeighed,
        MoneyView unitPrice,
        MoneyView lineTotal,
        MoneyView priceDelta,
        String priceSource,
        boolean consentRequired) {
    }

    /** The stored form. Kept separate from the wire record so a wire rename is not a data migration. */
    public record StoredConsent(String code, String lineId, Long fromPaise, Long toPaise) {
    }

    public record StoredOption(String mode, long totalPaise, String etaNote) {
    }

    public QuoteView of(QuoteSnapshot snapshot, Ward ward) {
        PriceQuote quote = snapshot.quote();
        return new QuoteView(
            new Quote(
                quote.getId(),
                quote.getScope().name(),
                quote.getState().name(),
                QuoteHash.display(quote.getQuoteHash()),
                quote.getIssuedAt(),
                quote.getExpiresAt(),
                quote.getFulfilmentModeSnapshot(),
                ward,
                new Totals(
                    MoneyView.of(Money.ofPaise(quote.getGoodsPaise())),
                    MoneyView.of(Money.ofPaise(quote.getDeliveryFeePaise())),
                    MoneyView.of(Money.ofPaise(quote.getHandlingFeePaise())),
                    MoneyView.of(Money.ofPaise(quote.getTotalPaise())),
                    MoneyView.of(Money.ofPaise(quote.getCommissionPaise()))),
                consents(quote.getConsentRequirementsJson()),
                options(quote.getFulfilmentOptionsJson())),
            snapshot.lines().stream().map(QuoteViews::line).toList());
    }

    public List<StoredConsent> readConsents(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return List.of();
        }
        return json.readValue(storedJson, new TypeReference<List<StoredConsent>>() { });
    }

    public List<StoredOption> readOptions(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return List.of();
        }
        return json.readValue(storedJson, new TypeReference<List<StoredOption>>() { });
    }

    private List<Consent> consents(String storedJson) {
        return readConsents(storedJson).stream()
            .map(c -> new Consent(c.code(), c.lineId(),
                c.fromPaise() == null ? null : MoneyView.of(Money.ofPaise(c.fromPaise())),
                c.toPaise() == null ? null : MoneyView.of(Money.ofPaise(c.toPaise()))))
            .toList();
    }

    private List<Option> options(String storedJson) {
        return readOptions(storedJson).stream()
            .map(o -> new Option(o.mode(), MoneyView.of(Money.ofPaise(o.totalPaise())), o.etaNote()))
            .toList();
    }

    private static Line line(PriceQuoteLine line) {
        boolean weighed = line.getQtyWeighed() != null;
        BigDecimal charged = weighed ? line.getQtyWeighed() : line.getQtyOrdered();
        return new Line(
            line.getId(),
            line.getProductCode(),
            line.getNameSnapshot(),
            line.getUnitLabelSnapshot(),
            line.getQtyOrdered(),
            line.getQtyWeighed(),
            MoneyView.of(Money.ofPaise(line.getUnitPricePaise()), weighed),
            MoneyView.of(Money.ofPaise(line.getLinePaise()), weighed),
            MoneyView.of(Money.ofPaise(line.getPriceDeltaPaise())),
            weighed ? "WEIGHED" : "LIST",
            line.isConsentRequired());
    }

    /** The line total the quote arithmetic uses; exposed so the cart does not repeat the rule. */
    public static long linePaise(BigDecimal chargedQty, long unitPricePaise) {
        return Money.ofPaise(unitPricePaise).multipliedBy(chargedQty).paise();
    }
}
