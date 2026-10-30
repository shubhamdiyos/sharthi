package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.quote.service.QuoteHash;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.tenancy.domain.ServiceWard;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The facade over {@link OrderWriter}: validates the request, canonicalises it for idempotency, and
 * raises the two refusals that need committed state behind them.
 *
 * <p>Deliberately <em>not</em> transactional. {@code PRICE_CHANGED} and {@code QUOTE_STALE} both
 * describe a transaction that has already done something worth keeping — a fresh quote issued, a
 * stale one expired — so the throw has to happen after that commit, not inside it.
 */
@Service
@RequiredArgsConstructor
public class OrderIntake {

    public static final String ENDPOINT = "POST /orders";

    private static final Pattern PINCODE = Pattern.compile("\\d{6}");

    private final OrderWriter writer;
    private final QuoteService quotes;

    /**
     * The request after normalisation. One record for what the HTTP layer sent and another for what
     * the database will see is what makes the idempotency hash stable across phone spellings.
     */
    public record Request(
        String quoteId,
        String quoteHash,
        Integer wardNo,
        String line1,
        String landmark,
        String pincode,
        String phoneE164,
        String deliveryNote,
        String mode) {
    }

    /** What the controller sends. */
    public record Body(String quoteId, String quoteHash, Integer wardNo, Address address,
                       String contactPhone, String deliveryNote, String fulfilmentMode) {
    }

    public record Address(String line1, String landmark, String pincode) {
    }

    public record Result(int status, String bodyJson, boolean replay) {
    }

    public Result place(AuthenticatedPrincipal caller, String idempotencyKey, Body body,
                        String requestId) {
        caller.require("ORDER_CREATE");
        String key = requireKey(idempotencyKey);
        Request request = normalise(caller, body);
        String hash = requestHash(caller, request);
        OrderWriter.Attempt attempt = writer.attempt(caller, key, request, hash, requestId);
        if (attempt instanceof OrderWriter.Attempt.Placed placed) {
            return new Result(201, placed.bodyJson(), false);
        }
        if (attempt instanceof OrderWriter.Attempt.Replayed replayed) {
            return new Result(200, replayed.bodyJson(), true);
        }
        if (attempt instanceof OrderWriter.Attempt.NeedsConsent consent) {
            throw priceChanged(consent);
        }
        if (attempt instanceof OrderWriter.Attempt.Expired expired) {
            throw new ApiException(ErrorCode.QUOTE_STALE, expired.message());
        }
        throw new IllegalStateException("unknown placement outcome: " + attempt);
    }

    private ApiException priceChanged(OrderWriter.Attempt.NeedsConsent consent) {
        ServiceWard ward = consent.ward();
        QuoteViews.QuoteView view = quotes.render(consent.quote(),
            new QuoteViews.Ward(ward.getWardNo(), ward.getName()));
        return new ApiException(ErrorCode.PRICE_CHANGED,
            "The price changed since you saw it. Review the new total.",
            Map.of(
                "quote", view,
                "quote_id", consent.quote().quote().getId(),
                "quote_hash", QuoteHash.display(consent.quote().quote().getQuoteHash())));
    }

    private String requireKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 200) {
            throw fieldError("Idempotency-Key",
                "is required and must be 8 to 200 characters");
        }
        return key;
    }

    private Request normalise(AuthenticatedPrincipal caller, Body body) {
        if (body == null) {
            throw fieldError("body", "is required");
        }
        if (body.quoteId() == null || body.quoteId().isBlank()
            || body.quoteId().length() > 64) {
            throw fieldError("quote_id", "is required");
        }
        if (body.quoteHash() == null || body.quoteHash().isBlank()) {
            throw fieldError("quote_hash", "is required");
        }
        if (body.address() == null) {
            throw fieldError("address", "is required");
        }
        String line1 = trimmed(body.address().line1());
        if (line1 == null || line1.length() > 200) {
            throw fieldError("address.line1", "is required and must be at most 200 characters");
        }
        String landmark = trimmed(body.address().landmark());
        if (landmark != null && landmark.length() > 200) {
            throw fieldError("address.landmark", "must be at most 200 characters");
        }
        String pincode = trimmed(body.address().pincode());
        if (pincode != null && !PINCODE.matcher(pincode).matches()) {
            throw fieldError("address.pincode", "must be six digits");
        }
        if (body.wardNo() != null && body.wardNo() <= 0) {
            throw fieldError("ward_no", "must be a ward number");
        }
        String phone = PhoneNormaliser.required(body.contactPhone(), "contact_phone");
        String note = trimmed(body.deliveryNote());
        if (note != null && note.length() > 300) {
            throw fieldError("delivery_note", "must be at most 300 characters");
        }
        String mode = null;
        if (body.fulfilmentMode() != null && !body.fulfilmentMode().isBlank()) {
            mode = body.fulfilmentMode().trim().toUpperCase(Locale.ROOT);
            if (!mode.equals("PLATFORM") && !mode.equals("MERCHANT")) {
                throw fieldError("fulfilment_mode", "must be PLATFORM or MERCHANT");
            }
        }
        if (caller.tenantId() == null) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, "Choose your area before you order.");
        }
        return new Request(body.quoteId().trim(), body.quoteHash().trim(), body.wardNo(),
            line1, landmark, pincode, phone, note, mode);
    }

    /**
     * Canonical text, hashed. The customer id is part of it because the record's key is
     * {@code (tenant, key, endpoint)}: two customers who happened to choose the same key must
     * collide as a duplicate, never replay each other's order.
     */
    private static String requestHash(AuthenticatedPrincipal caller, Request request) {
        String canonical = String.join("\n",
            "v1",
            caller.accountId(),
            request.quoteId(),
            QuoteHash.bare(request.quoteHash()),
            request.wardNo() == null ? "" : request.wardNo().toString(),
            request.line1(),
            nullToEmpty(request.landmark()),
            nullToEmpty(request.pincode()),
            request.phoneE164(),
            nullToEmpty(request.deliveryNote()),
            nullToEmpty(request.mode()));
        return QuoteHash.sha256(canonical);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }
}
