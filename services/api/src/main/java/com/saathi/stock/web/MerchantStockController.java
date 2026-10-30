package com.saathi.stock.web;

import com.saathi.catalog.web.CatalogViews;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.Page;
import com.saathi.stock.service.StockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.6's stock endpoints. The path names a product because the adjustment is about one item;
 * the shop is still the session's, never the caller's choice.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MerchantStockController {

    private final StockService stock;
    private final RequestTags tags;

    @GetMapping("/m/stock")
    public Page<CatalogViews.StockView> shelves(
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit,
        AuthenticatedPrincipal caller) {
        return stock.shelves(caller, state, cursor, limit);
    }

    @PostMapping("/m/products/{code}/stock")
    public CatalogViews.StockView adjust(@PathVariable("code") String code,
                                         @Valid @RequestBody StockService.Adjustment body,
                                         AuthenticatedPrincipal caller,
                                         HttpServletRequest request) {
        return stock.adjust(caller, code, body, MDC.get(RequestIdFilter.MDC_KEY),
            tags.of(request).ipHash());
    }

    @GetMapping("/m/products/{code}/stock")
    public CatalogViews.StockView stock(@PathVariable("code") String code,
                                        AuthenticatedPrincipal caller) {
        return stock.stockOf(caller, code);
    }
}
