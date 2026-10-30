package com.saathi.catalog.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.repository.ProductRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue as the cart and the order see it: products by their public code, and the row
 * locks §7.3 requires before money is totalled.
 *
 * <p>This exists so {@code cart/} and {@code order/} never touch {@code product} themselves (§3).
 * A lock taken through this service is the same lock {@link StockBook} takes — the point is that
 * there is one implementation, not that there are two doors.
 */
@Service
@RequiredArgsConstructor
public class CatalogShelf {

    private final ProductRepository products;
    private final StockBook book;

    /** The platform's product codes are unique, so a code names one row without a city. */
    @Transactional(readOnly = true)
    public Product byCode(String code) {
        return products.findByCode(code == null ? "" : code.trim())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that item."));
    }

    /** The products behind a basket's line ids, in one query, scoped to the caller's city. */
    @Transactional(readOnly = true)
    public List<Product> byIds(String tenantId, List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return products.findByIdInAndTenantId(productIds, tenantId);
    }

    /** MANDATORY: the products a quote is about to price, locked in ascending id order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Product> lockForQuote(String tenantId, List<String> productIds) {
        return book.lockForUpdate(tenantId, productIds);
    }
}
