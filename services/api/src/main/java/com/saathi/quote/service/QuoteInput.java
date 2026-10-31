package com.saathi.quote.service;

import com.saathi.quote.domain.QuoteScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the quote module needs to total money, and nothing it would have to ask another module
 * for. The caller computes the fee, the mode and the line snapshots; this record is the boundary
 * that keeps {@code quote/} from reading {@code cart/}, {@code merchant/} or {@code tenancy/} (§3).
 */
public record QuoteInput(
    String tenantId,
    String cartId,
    String orderId,
    QuoteScope scope,
    String mode,
    long deliveryFeePaise,
    long handlingFeePaise,
    long commissionPaise,
    /**
     * How long the total stays acceptable. A cart quote always has one; a {@code WEIGHT} quote
     * passes null, because §6.5 forbids a price-confirmation countdown and §10.6.2 promises the
     * order waits indefinitely — an expiring consent request would leave an accepted order with no
     * total the customer is still allowed to accept.
     */
    Integer ttlMinutes,
    Instant issuedAt,
    List<Line> lines,
    List<Consent> consents,
    List<Option> options) {

    public record Line(
        String productId,
        String productCode,
        String name,
        String unitLabel,
        BigDecimal qtyOrdered,
        BigDecimal qtyWeighed,
        long unitPricePaise,
        long priceDeltaPaise,
        boolean consentRequired) {

        public BigDecimal chargedQty() {
            return qtyWeighed == null ? qtyOrdered : qtyWeighed;
        }
    }

    /**
     * §8.1's server-computed consent requirement. {@code productId} is translated to the quote
     * line's own id when the requirement is stored, because the customer consents to a quote line.
     *
     * <p>A null {@code productId} is an order-level requirement — a fee or fulfilment change, which
     * belongs to no single line — and stores with a null {@code line_id}. §10.4's consent entries
     * carry {@code from} and {@code to} either way, so a client renders both kinds from one list.
     */
    public record Consent(String code, String productId, Long fromPaise, Long toPaise) {
    }

    /** §10.4's {@code fulfilment_options[]}; frozen at issue so a later fee edit cannot rewrite it. */
    public record Option(String mode, long totalPaise, String etaNote) {
    }
}
