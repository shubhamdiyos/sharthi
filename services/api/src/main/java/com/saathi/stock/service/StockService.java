package com.saathi.stock.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.service.StockBook;
import com.saathi.catalog.web.CatalogViews;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantOnboarding;
import com.saathi.shared.audit.AuditEntry;
import com.saathi.shared.audit.AuditRecorder;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import com.saathi.stock.domain.StockAdjustment;
import com.saathi.stock.repository.StockLedger;
import java.math.BigDecimal;
import java.time.Clock;
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
 * The merchant's stock screen (§10.5.6).
 *
 * <p>An adjustment is a delta with a sentence, never a new total: the row it writes is the only
 * record of why a count changed, and §13.2's {@code STOCK_ADJUSTED} is what makes "three packets
 * went missing" findable months later. The count itself moves through {@link StockBook}, which is
 * the only code allowed to write {@code product.stock_qty} (§3, §7.3).
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final int REASON_MAX = 200;
    private static final BigDecimal MAX_ABS_DELTA = new BigDecimal("1000000");

    private final com.saathi.catalog.repository.ProductRepository products;
    private final StockBook book;
    private final StockLedger ledger;
    private final MerchantOnboarding onboarding;
    private final AuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    /** One explained change: {@code +12} for a delivery intake, {@code -2} for spoilage. */
    public record Adjustment(BigDecimal deltaQty, String reason) {
    }

    @Transactional
    public CatalogViews.StockView adjust(AuthenticatedPrincipal caller, String code, Adjustment body,
                                         String requestId, String ipHash) {
        caller.require("STOCK_ADJUST");
        Merchant merchant = onboarding.ownMerchant(caller);
        Product product = products.findByMerchantIdAndCode(merchant.getId(),
                code == null ? "" : code.trim())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That item is not in your shop."));

        if (body == null || body.deltaQty() == null || body.deltaQty().signum() == 0) {
            throw fieldError("delta_qty", "must be a non-zero change");
        }
        if (body.deltaQty().abs().compareTo(MAX_ABS_DELTA) > 0) {
            throw fieldError("delta_qty", "is larger than any real delivery or loss");
        }
        String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.length() < 3 || reason.length() > REASON_MAX) {
            throw fieldError("reason", "must be 3 to " + REASON_MAX + " characters");
        }
        if (!product.isTrackStock()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "This product does not track stock; turn counting on before adjusting it.");
        }

        BigDecimal before = product.getStockQty();
        Product adjusted = book.adjust(merchant.getTenantId(), product.getId(), body.deltaQty());

        ledger.record(StockAdjustment.builder()
            .id(Ulid.next(clock))
            .tenantId(merchant.getTenantId())
            .productId(product.getId())
            .deltaQty(body.deltaQty())
            .reason(reason)
            .actorId(caller.accountId())
            .at(clock.instant())
            .build());

        Map<String, Object> beforeJson = new LinkedHashMap<>();
        beforeJson.put("code", product.getCode());
        beforeJson.put("stock_qty", before);
        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put("code", product.getCode());
        afterJson.put("stock_qty", adjusted.getStockQty());

        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action("STOCK_ADJUSTED")
            .entity("product")
            .entityId(product.getId())
            .beforeJson(json.writeValueAsString(beforeJson))
            .afterJson(json.writeValueAsString(afterJson))
            .reasonCode(reason)
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
        return CatalogViews.StockView.of(adjusted);
    }

    /**
     * {@code GET /m/stock?state=low|out}. The shelves are small by definition, and their sort is
     * {@code (stock_qty, name)} rather than name — so the cursor is applied over the returned list
     * instead of in SQL, exactly as {@code GET /auth/sessions} does for the same reason.
     */
    @Transactional(readOnly = true)
    public Page<CatalogViews.StockView> shelves(AuthenticatedPrincipal caller, String state,
                                                String cursor, Integer limit) {
        caller.require("STOCK_READ");
        Merchant merchant = onboarding.ownMerchant(caller);
        String shelf = state == null || state.isBlank() ? "low" : state.trim().toLowerCase(Locale.ROOT);
        if (!shelf.equals("low") && !shelf.equals("out")) {
            throw fieldError("state", "must be low or out");
        }
        int size = CursorPager.clampLimit(limit);
        List<Product> rows = products.stockShelves(merchant.getId(), shelf,
            PageRequest.of(0, CursorPager.MAX_LIMIT));
        List<String> keys = CursorPager.decode(cursor);
        int start = 0;
        if (keys.size() == 3 && keys.get(0) != null && keys.get(1) != null) {
            BigDecimal afterQty = new BigDecimal(keys.get(0));
            String afterName = keys.get(1);
            String afterId = keys.get(2);
            while (start < rows.size()) {
                Product row = rows.get(start);
                int byQty = row.getStockQty().compareTo(afterQty);
                int byName = row.getName().compareTo(afterName);
                if (byQty > 0 || (byQty == 0 && (byName > 0
                    || (byName == 0 && row.getId().compareTo(afterId) > 0)))) {
                    break;
                }
                start++;
            }
        } else if (!keys.isEmpty()) {
            throw CursorPager.badCursor();
        }
        int end = Math.min(start + CursorPager.fetchSize(size), rows.size());
        List<Product> window = rows.subList(start, end);
        boolean hasMore = window.size() > size;
        List<Product> page = hasMore ? window.subList(0, size) : window;
        String next = hasMore ? CursorPager.encode(List.of(
            page.get(page.size() - 1).getStockQty().toPlainString(),
            page.get(page.size() - 1).getName(),
            page.get(page.size() - 1).getId())) : null;
        return Page.of(page.stream().map(CatalogViews.StockView::of).toList(), next, hasMore, size);
    }

    /** One item's counts, for the stock screen's detail sheet. */
    @Transactional(readOnly = true)
    public CatalogViews.StockView stockOf(AuthenticatedPrincipal caller, String code) {
        caller.require("STOCK_READ");
        Merchant merchant = onboarding.ownMerchant(caller);
        Product product = products.findByMerchantIdAndCode(merchant.getId(),
                code == null ? "" : code.trim())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That item is not in your shop."));
        return CatalogViews.StockView.of(product);
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }
}
