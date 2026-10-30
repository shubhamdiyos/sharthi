package com.saathi.merchant.service;

/**
 * The one catalogue fact a shop screen prints: how many items the shop is selling.
 *
 * <p>{@code product} is catalog's table, so the count is catalog's answer. This port exists so the
 * dependency runs one way — catalog reads shops, and merchant asks the catalogue for a number it
 * cannot compute — instead of the two modules owning each other (System Design §3, enforced by
 * {@code ModuleBoundaryTest}).
 */
public interface ShopProductCounts {

    long activeProductsOf(String merchantId);
}
