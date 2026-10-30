package com.saathi.catalog.service;

import com.saathi.catalog.repository.ProductRepository;
import com.saathi.merchant.service.ShopProductCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Catalog's side of {@link ShopProductCounts}: a count of rows this module owns. */
@Component
@RequiredArgsConstructor
class CatalogProductCounts implements ShopProductCounts {

    private final ProductRepository products;

    @Override
    public long activeProductsOf(String merchantId) {
        return products.countByMerchantIdAndActiveTrue(merchantId);
    }
}
