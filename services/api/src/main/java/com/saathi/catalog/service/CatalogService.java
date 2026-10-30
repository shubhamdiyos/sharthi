package com.saathi.catalog.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.repository.ProductRepository;
import com.saathi.catalog.web.CatalogViews;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.merchant.web.ShopViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.domain.TenantStatus;
import com.saathi.tenancy.service.ServiceabilityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public catalogue (§10.5.3).
 *
 * <p>Every read here is reachable without a session, so the rules are stated in terms of rows
 * rather than permissions: a product is visible when it is active, available, belongs to a shop
 * that is visible in a live city, and (for the listing) sits in a category that city has switched
 * on. A product in a hidden shop answers {@code NOT_FOUND}, not "this exists but you may not see
 * it" — §4.3-5 treats visibility as scope.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    /** Search shows at most this many shops before deferring to the shops screen. */
    private static final int SEARCH_SHOP_LIMIT = 5;

    private final ProductRepository products;
    private final MerchantDirectory directory;
    private final ServiceabilityService serviceability;

    /** {@code GET /shops/{code}/products}: one shop's sellable catalogue, keyset-paged. */
    @Transactional(readOnly = true)
    public Page<CatalogViews.ProductSummary> shopProducts(String cityCode, String shopCode,
                                                          String categorySlug, String q, String cursor,
                                                          Integer limit) {
        Merchant merchant = directory.visibleShop(cityCode, shopCode);
        Tenant tenant = serviceability.liveTenantById(merchant.getTenantId());
        String categoryId = serviceability.categoryIdOf(tenant, categorySlug);
        int size = CursorPager.clampLimit(limit);
        List<String> keys = CursorPager.decode(cursor);
        String afterName = keys.size() > 0 ? keys.get(0) : "";
        String afterId = keys.size() > 1 ? keys.get(1) : "";
        String query = q == null ? "" : q.trim();

        List<Product> rows = products.shopListing(merchant.getId(), categoryId, query,
            query.isEmpty() ? null : "%" + query.toLowerCase() + "%",
            afterName, afterId, PageRequest.of(0, CursorPager.fetchSize(size)));
        return pageOf(rows, size, p -> CatalogViews.ProductSummary.of(p));
    }

    /**
     * {@code GET /products/{code}}. Product codes are platform-unique, so the URL names a row
     * without a city; the optional {@code city_code} is then a check, not a lookup. When it is
     * absent the product's own city must be live — the same answer the shop list would give.
     */
    @Transactional(readOnly = true)
    public CatalogViews.ProductDetail productDetail(String cityCode, String code) {
        String productCode = code == null ? "" : code.trim();
        Product product = products.findByCode(productCode)
            .orElseThrow(() -> notFound());
        Tenant tenant = serviceability.tenantById(product.getTenantId());
        if (cityCode != null && !cityCode.isBlank()) {
            Tenant named = serviceability.liveTenant(cityCode);
            if (!named.getId().equals(tenant.getId())) {
                throw notFound();
            }
        } else if (tenant.getStatus() != TenantStatus.LIVE) {
            throw notFound();
        }
        Merchant merchant = directory.visibleMerchantById(tenant.getId(), product.getMerchantId())
            .orElseThrow(CatalogService::notFound);
        if (!product.isActive()) {
            throw notFound();
        }
        String[] label = labelOf(tenant, product.getCategoryId());
        return new CatalogViews.ProductDetail(
            product.getCode(),
            merchant.getCode(),
            merchant.getTradeName(),
            label[0],
            label[1],
            product.getName(),
            product.getDescription(),
            product.getUnitLabel(),
            CatalogViews.price(product),
            product.isByWeight(),
            product.getWeightGrams(),
            product.getPackQty(),
            product.isAvailable(),
            product.getMaxQtyPerOrder(),
            product.getImageKey(),
            product.isByWeight() ? CatalogViews.WEIGHT_CONSENT_NOTICE : null);
    }

    /**
     * {@code GET /search}: matching shops and matching products over one page. With a city it is
     * one tenant's answer; without one it is every live city's, merged on the same
     * {@code (name, id)} sort so the cursor means the same thing either way.
     *
     * <p>A blank query returns nothing rather than the whole catalogue: the storefront calls this
     * as the customer types, and an empty box is not a request for every product in Siwan.
     */
    @Transactional(readOnly = true)
    public CatalogViews.SearchView search(String cityCode, Integer wardNo, String q, String cursor,
                                          Integer limit) {
        int size = CursorPager.clampLimit(limit);
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return new CatalogViews.SearchView(List.of(), Page.empty(size));
        }
        List<Tenant> tenants = cityCode == null || cityCode.isBlank()
            ? serviceability.liveCities()
            : List.of(serviceability.liveTenant(cityCode));

        List<ShopViews.Summary> shops = new ArrayList<>();
        List<Product> found = new ArrayList<>();
        for (Tenant tenant : tenants) {
            shops.addAll(directory.shops(tenant.getCode(), wardNo, null, query, null,
                SEARCH_SHOP_LIMIT).data());
            ServiceWard ward = wardNo == null ? null : serviceability.servedWard(tenant, wardNo);
            List<String> merchantIds = directory.visibleMerchantIds(tenant.getId(),
                ward == null ? null : ward.getId());
            if (!merchantIds.isEmpty()) {
                found.addAll(products.search(tenant.getId(), merchantIds,
                    "%" + query.toLowerCase() + "%", "", "",
                    PageRequest.of(0, CursorPager.MAX_LIMIT)));
            }
        }

        shops.sort(Comparator.comparing(ShopViews.Summary::name).thenComparing(ShopViews.Summary::code));
        List<ShopViews.Summary> shopSection = shops.size() > SEARCH_SHOP_LIMIT
            ? shops.subList(0, SEARCH_SHOP_LIMIT) : shops;

        found.sort(Comparator.comparing(Product::getName).thenComparing(Product::getId));
        List<String> keys = CursorPager.decode(cursor);
        String afterName = keys.size() > 0 ? keys.get(0) : "";
        String afterId = keys.size() > 1 ? keys.get(1) : "";
        List<Product> afterCursor = found.stream()
            .filter(p -> afterName.isEmpty()
                || p.getName().compareTo(afterName) > 0
                || (p.getName().equals(afterName) && p.getId().compareTo(afterId) > 0))
            .toList();
        boolean hasMore = afterCursor.size() > size;
        List<Product> window = hasMore ? afterCursor.subList(0, size) : afterCursor;

        Page<CatalogViews.ProductSummary> productPage;
        if (hasMore) {
            Product last = window.get(window.size() - 1);
            productPage = Page.of(
                window.stream().map(CatalogViews.ProductSummary::of).toList(),
                CursorPager.encode(List.of(last.getName(), last.getId())), true, size);
        } else {
            productPage = Page.of(window.stream().map(CatalogViews.ProductSummary::of).toList(),
                null, false, size);
        }
        return new CatalogViews.SearchView(shopSection, productPage);
    }

    private Page<CatalogViews.ProductSummary> pageOf(List<Product> rows, int size,
                                                     java.util.function.Function<Product,
                                                         CatalogViews.ProductSummary> mapper) {
        boolean hasMore = rows.size() > size;
        List<Product> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
            ? CursorPager.encode(List.of(page.get(page.size() - 1).getName(),
                page.get(page.size() - 1).getId()))
            : null;
        return Page.of(page.stream().map(mapper).toList(), next, hasMore, size);
    }

    private String[] labelOf(Tenant tenant, String categoryId) {
        return serviceability.activatedCategories(tenant).stream()
            .filter(c -> c.category().getId().equals(categoryId))
            .map(c -> new String[] {c.slug(), c.displayName()})
            .findFirst()
            .orElse(new String[] {"unknown", "Other"});
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "We could not find that item.");
    }
}
