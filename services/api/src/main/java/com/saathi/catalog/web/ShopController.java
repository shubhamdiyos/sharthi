package com.saathi.catalog.web;

import com.saathi.catalog.service.CatalogService;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.merchant.web.ShopViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.paging.Page;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.3, the whole storefront read surface. No session is required and no permission is checked:
 * everything here answers the same way to a guest and to a signed-in customer, which is what makes
 * the storefront cacheable and the catalogue honest.
 *
 * <p>{@code city_code} is optional on the detail endpoints because a shop or a product code is
 * already a public identifier; when it is present it is a second opinion, and a code that belongs
 * to a different city is {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ShopController {

    private final MerchantDirectory directory;
    private final CatalogService catalog;

    /**
     * {@code sort} exists in §10.5.3's signature but the only order this build implements is by
     * name. Naming a different one is refused rather than silently ignored: a screen that asked
     * for "nearest" and got alphabetical would look like a ranking bug.
     */
    @GetMapping("/shops")
    public Page<ShopViews.Summary> shops(
        @RequestParam(name = "city_code", required = false) String cityCode,
        @RequestParam(name = "ward_no", required = false) Integer wardNo,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "sort", required = false) String sort,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit) {
        requireNameSort(sort);
        return directory.shops(cityCode, wardNo, category, q, cursor, limit);
    }

    @GetMapping("/shops/{code}")
    public ShopViews.Detail shop(@PathVariable("code") String code,
                                 @RequestParam(name = "city_code", required = false) String cityCode) {
        return directory.detail(cityCode, code);
    }

    @GetMapping("/shops/{code}/products")
    public Page<CatalogViews.ProductSummary> shopProducts(
        @PathVariable("code") String code,
        @RequestParam(name = "city_code", required = false) String cityCode,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "sort", required = false) String sort,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit) {
        requireNameSort(sort);
        return catalog.shopProducts(cityCode, code, category, q, cursor, limit);
    }

    @GetMapping("/products/{code}")
    public CatalogViews.ProductDetail product(@PathVariable("code") String code,
                                              @RequestParam(name = "city_code", required = false) String cityCode) {
        return catalog.productDetail(cityCode, code);
    }

    @GetMapping("/search")
    public CatalogViews.SearchView search(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "city_code", required = false) String cityCode,
        @RequestParam(name = "ward_no", required = false) Integer wardNo,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit) {
        return catalog.search(cityCode, wardNo, q, cursor, limit);
    }

    private static void requireNameSort(String sort) {
        if (sort != null && !sort.isBlank() && !sort.trim().equalsIgnoreCase("name")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "sort",
                    "message", "the only supported order is name"))));
        }
    }
}
