package com.saathi.catalog.service;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.domain.StockUnit;
import com.saathi.catalog.repository.ProductRepository;
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
import com.saathi.tenancy.domain.Category;
import com.saathi.tenancy.domain.TenantCategory;
import com.saathi.tenancy.repository.CategoryRepository;
import com.saathi.tenancy.repository.TenantCategoryRepository;
import com.saathi.tenancy.service.ServiceabilityService;
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
 * The merchant's own catalogue (§10.5.6).
 *
 * <p>Three rules shape every write here. A product belongs to the shop the session is bound to,
 * never to a shop code in the body. A category must be one this city has switched on (§4.1), which
 * the database also enforces with a trigger — this check exists so the merchant gets
 * {@code CATEGORY_NOT_ACTIVE} instead of a constraint violation. And the unit the shop counts in
 * must be a unit its label names (§7.1): "500 g" counted in {@code kg} is how a customer orders a
 * half-kilo and is charged for four kilos.
 *
 * <p>Prices are edited, never rewritten in history: an order keeps the price it was quoted (D4,
 * §8), so the only thing a price edit changes is what the next quote will say.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final BigDecimal MAX_QTY = new BigDecimal("999");
    private static final int MAX_NAME = 160;
    private static final int MAX_DESCRIPTION = 1_000;

    private final ProductRepository products;
    private final MerchantOnboarding onboarding;
    private final CategoryRepository categories;
    private final TenantCategoryRepository tenantCategories;
    private final ServiceabilityService serviceability;
    private final AuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    /** Everything a shop may decide when it creates an item. */
    public record Create(
        String name,
        String description,
        String categorySlug,
        Long pricePaise,
        String unitLabel,
        Boolean isByWeight,
        BigDecimal weightGrams,
        BigDecimal packQty,
        Boolean trackStock,
        BigDecimal stockQty,
        String stockUnit,
        Boolean allowOversell,
        Integer maxQtyPerOrder,
        String imageKey) {
    }

    /** Absent fields stay as they are; the {@code If-Match} version guards the rest. */
    public record Update(
        String name,
        String description,
        String categorySlug,
        Long pricePaise,
        String unitLabel,
        Boolean isByWeight,
        BigDecimal weightGrams,
        BigDecimal packQty,
        Boolean trackStock,
        String stockUnit,
        Boolean allowOversell,
        Integer maxQtyPerOrder,
        String imageKey) {
    }

    @Transactional
    public Product create(AuthenticatedPrincipal caller, Create body, String requestId, String ipHash) {
        caller.require("PRODUCT_WRITE");
        Merchant merchant = onboarding.ownMerchant(caller);

        String name = requireText(body.name(), "name", MAX_NAME);
        String unitLabel = requireText(body.unitLabel(), "unit_label", 40);
        long pricePaise = requirePrice(body.pricePaise());
        boolean byWeight = Boolean.TRUE.equals(body.isByWeight());
        boolean trackStock = Boolean.TRUE.equals(body.trackStock());
        StockUnit stockUnit = parseStockUnit(body.stockUnit() == null ? "pc" : body.stockUnit());
        requireCoherent(unitLabel, stockUnit, byWeight);
        BigDecimal stockQty = nonNegative(body.stockQty(), "stock_qty");
        Category category = activeCategory(merchant.getTenantId(), body.categorySlug());

        Product product = Product.builder()
            .id(Ulid.next(clock))
            .tenantId(merchant.getTenantId())
            .merchantId(merchant.getId())
            .categoryId(category.getId())
            .code(uniqueCode(name))
            .name(name)
            .description(trimmed(body.description()))
            .imageKey(trimmed(body.imageKey()))
            .pricePaise(pricePaise)
            .currency("INR")
            .unitLabel(unitLabel)
            .byWeight(byWeight)
            .weightGrams(positiveOrNull(body.weightGrams(), "weight_grams"))
            .packQty(positiveOrNull(body.packQty(), "pack_qty"))
            .trackStock(trackStock)
            // The initial count is part of creation, not an adjustment: there is no earlier
            // value to explain, and stock_adjustment exists to explain a change.
            .stockQty(trackStock ? stockQty : BigDecimal.ZERO)
            .reservedQty(BigDecimal.ZERO)
            .stockUnit(stockUnit.name())
            .allowOversell(Boolean.TRUE.equals(body.allowOversell()))
            .maxQtyPerOrder(maxQty(body.maxQtyPerOrder()))
            .available(true)
            .active(true)
            .createdAt(clock.instant())
            .updatedAt(clock.instant())
            .build();
        products.save(product);

        record(caller, merchant, product, "PRODUCT_CREATED", null, requestId, ipHash,
            "Product created");
        return product;
    }

    @Transactional
    public Product update(AuthenticatedPrincipal caller, String code, Update body, long expectedVersion,
                          String requestId, String ipHash) {
        caller.require("PRODUCT_WRITE");
        Merchant merchant = onboarding.ownMerchant(caller);
        Product product = owned(merchant, code);
        requireVersion(product, expectedVersion);

        String before = snapshot(product);
        long oldPrice = product.getPricePaise();
        boolean oldAvailable = product.isAvailable();
        boolean otherChanged = false;

        if (body.name() != null) {
            product.setName(requireText(body.name(), "name", MAX_NAME));
            otherChanged = true;
        }
        if (body.description() != null) {
            String description = trimmed(body.description());
            if (description != null && description.length() > MAX_DESCRIPTION) {
                throw fieldError("description", "must be at most " + MAX_DESCRIPTION + " characters");
            }
            product.setDescription(description);
            otherChanged = true;
        }
        if (body.categorySlug() != null) {
            Category category = activeCategory(merchant.getTenantId(), body.categorySlug());
            if (!category.getId().equals(product.getCategoryId())) {
                product.setCategoryId(category.getId());
                otherChanged = true;
            }
        }
        if (body.pricePaise() != null) {
            product.setPricePaise(requirePrice(body.pricePaise()));
        }
        if (body.unitLabel() != null) {
            product.setUnitLabel(requireText(body.unitLabel(), "unit_label", 40));
            otherChanged = true;
        }
        if (body.stockUnit() != null) {
            product.setStockUnit(parseStockUnit(body.stockUnit()).name());
            otherChanged = true;
        }
        if (body.isByWeight() != null) {
            product.setByWeight(body.isByWeight());
            otherChanged = true;
        }
        if (body.weightGrams() != null) {
            product.setWeightGrams(positiveOrNull(body.weightGrams(), "weight_grams"));
            otherChanged = true;
        }
        if (body.packQty() != null) {
            product.setPackQty(positiveOrNull(body.packQty(), "pack_qty"));
            otherChanged = true;
        }
        if (body.trackStock() != null) {
            product.setTrackStock(body.trackStock());
            otherChanged = true;
        }
        if (body.allowOversell() != null) {
            product.setAllowOversell(body.allowOversell());
            otherChanged = true;
        }
        if (body.maxQtyPerOrder() != null) {
            product.setMaxQtyPerOrder(maxQty(body.maxQtyPerOrder()));
            otherChanged = true;
        }
        if (body.imageKey() != null) {
            product.setImageKey(trimmed(body.imageKey()));
            otherChanged = true;
        }

        requireCoherent(product.getUnitLabel(), parseStockUnit(product.getStockUnit()),
            product.isByWeight());
        product.setUpdatedAt(clock.instant());
        products.save(product);

        if (product.getPricePaise() != oldPrice) {
            record(caller, merchant, product, "PRICE_CHANGED", before, requestId, ipHash,
                "Merchant changed the price");
        }
        if (product.isAvailable() != oldAvailable) {
            record(caller, merchant, product, "AVAILABILITY_CHANGED", before, requestId, ipHash,
                product.isAvailable() ? "Shop made the item available" : "Shop marked it unavailable");
        }
        if (otherChanged) {
            record(caller, merchant, product, "PRODUCT_UPDATED", before, requestId, ipHash,
                "Merchant edited the product");
        }
        return product;
    }

    /**
     * {@code POST /m/products/{code}/availability}. There is no delete (PRD §8.3): an item a shop
     * stops selling is switched off and every order that already contains it still renders.
     */
    @Transactional
    public Product setAvailability(AuthenticatedPrincipal caller, String code, boolean available,
                                   String requestId, String ipHash) {
        caller.require("PRODUCT_WRITE");
        Merchant merchant = onboarding.ownMerchant(caller);
        Product product = owned(merchant, code);
        if (product.isAvailable() == available) {
            return product;
        }
        String before = snapshot(product);
        product.setAvailable(available);
        product.setUpdatedAt(clock.instant());
        products.save(product);
        record(caller, merchant, product, "AVAILABILITY_CHANGED", before, requestId, ipHash,
            available ? "Shop made the item available" : "Shop marked it unavailable");
        return product;
    }

    @Transactional
    public Product deactivate(AuthenticatedPrincipal caller, String code, String requestId, String ipHash) {
        caller.require("PRODUCT_WRITE");
        Merchant merchant = onboarding.ownMerchant(caller);
        Product product = owned(merchant, code);
        if (!product.isActive()) {
            return product;
        }
        String before = snapshot(product);
        product.setActive(false);
        product.setUpdatedAt(clock.instant());
        products.save(product);
        record(caller, merchant, product, "PRODUCT_DEACTIVATED", before, requestId, ipHash,
            "Merchant retired the item");
        return product;
    }

    @Transactional(readOnly = true)
    public Page<CatalogViews.ProductView> list(AuthenticatedPrincipal caller, String state, String q,
                                               String cursor, Integer limit) {
        caller.require("PRODUCT_READ");
        Merchant merchant = onboarding.ownMerchant(caller);
        String stateFilter = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (!stateFilter.isEmpty()
            && !List.of("ACTIVE", "UNAVAILABLE", "INACTIVE").contains(stateFilter)) {
            throw fieldError("state", "must be ACTIVE, UNAVAILABLE or INACTIVE");
        }
        int size = CursorPager.clampLimit(limit);
        List<String> keys = CursorPager.decode(cursor);
        String afterName = keys.size() > 0 ? keys.get(0) : "";
        String afterId = keys.size() > 1 ? keys.get(1) : "";
        String query = q == null ? "" : q.trim();

        List<Product> rows = products.merchantListing(merchant.getId(), stateFilter, query,
            query.isEmpty() ? null : "%" + query.toLowerCase(Locale.ROOT) + "%",
            afterName, afterId, PageRequest.of(0, CursorPager.fetchSize(size)));
        boolean hasMore = rows.size() > size;
        List<Product> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
            ? CursorPager.encode(List.of(page.get(page.size() - 1).getName(),
                page.get(page.size() - 1).getId()))
            : null;
        Map<String, String[]> labels = new java.util.HashMap<>();
        for (var activated : serviceability.activatedCategories(
            serviceability.tenantById(merchant.getTenantId()))) {
            labels.put(activated.category().getId(),
                new String[] {activated.slug(), activated.displayName()});
        }
        return Page.of(page.stream()
            .map(p -> view(p, labels.get(p.getCategoryId())))
            .toList(), next, hasMore, size);
    }

    @Transactional(readOnly = true)
    public Product owned(Merchant merchant, String code) {
        return products.findByMerchantIdAndCode(merchant.getId(), code == null ? "" : code.trim())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That item is not in your shop."));
    }

    /** The single-item response, with the city's own name for the category (§4.1). */
    public CatalogViews.ProductView view(Product product) {
        String[] label = serviceability.activatedCategories(
                serviceability.tenantById(product.getTenantId())).stream()
            .filter(c -> c.category().getId().equals(product.getCategoryId()))
            .map(c -> new String[] {c.slug(), c.displayName()})
            .findFirst()
            .orElse(new String[] {"unknown", "Other"});
        return view(product, label);
    }

    public CatalogViews.ProductView view(Product product, String[] categoryLabel) {
        return new CatalogViews.ProductView(
            product.getCode(),
            product.getName(),
            product.getDescription(),
            categoryLabel == null ? "unknown" : categoryLabel[0],
            categoryLabel == null ? "Other" : categoryLabel[1],
            product.getUnitLabel(),
            CatalogViews.price(product),
            product.isByWeight(),
            product.getWeightGrams(),
            product.getPackQty(),
            product.isTrackStock(),
            product.getStockQty(),
            product.getReservedQty(),
            CatalogViews.availableQty(product),
            product.getStockUnit(),
            product.isAllowOversell(),
            product.getMaxQtyPerOrder(),
            product.isAvailable(),
            product.isActive(),
            product.stockState(),
            product.getImageKey(),
            product.getVersion(),
            product.getUpdatedAt());
    }

    /** §10.5.6's optimistic lock: a stale {@code If-Match} is a lost update, not a lost field. */
    private static void requireVersion(Product product, long expected) {
        if (product.getVersion() != expected) {
            throw new ApiException(ErrorCode.CONFLICT,
                "This item changed since you loaded it. Reload and try again.",
                Map.of("current_version", product.getVersion()));
        }
    }

    private Category activeCategory(String tenantId, String slug) {
        if (slug == null || slug.isBlank()) {
            throw fieldError("category_slug", "is required");
        }
        Category category = categories.findBySlug(slug.trim().toLowerCase(Locale.ROOT))
            .filter(Category::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE,
                "That category is not available."));
        tenantCategories.findByTenantIdAndCategoryId(tenantId, category.getId())
            .filter(TenantCategory::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE,
                "That category is not active in this city."));
        return category;
    }

    /** §7.1's unit coercion, applied to the merged row so a one-field patch cannot break it. */
    private static void requireCoherent(String unitLabel, StockUnit stockUnit, boolean byWeight) {
        if (byWeight && stockUnit != StockUnit.kg && stockUnit != StockUnit.g) {
            throw fieldError("stock_unit", "a by-weight item is counted in kg or g");
        }
        if (!stockUnit.describes(unitLabel)) {
            throw fieldError("unit_label",
                "must name the unit the item is counted in (" + stockUnit.name() + ")");
        }
    }

    private String uniqueCode(String name) {
        String base = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (base.length() < 3) {
            throw fieldError("name", "needs at least three letters");
        }
        if (base.length() > 90) {
            base = base.substring(0, 90).replaceAll("-$", "");
        }
        String candidate = base;
        for (int suffix = 2; products.findByCode(candidate).isPresent(); suffix++) {
            candidate = base + "-" + suffix;
            if (suffix > 99) {
                throw new ApiException(ErrorCode.CONFLICT,
                    "Too many items share that name; add a distinguishing word.");
            }
        }
        return candidate;
    }

    private void record(AuthenticatedPrincipal caller, Merchant merchant, Product product,
                        String action, String before, String requestId, String ipHash, String reason) {
        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action(action)
            .entity("product")
            .entityId(product.getId())
            .beforeJson(before)
            .afterJson(snapshot(product))
            .reasonCode(reason)
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
    }

    public String snapshot(Product product) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", product.getCode());
        values.put("name", product.getName());
        values.put("price_paise", product.getPricePaise());
        values.put("unit_label", product.getUnitLabel());
        values.put("available", product.isAvailable());
        values.put("active", product.isActive());
        values.put("track_stock", product.isTrackStock());
        return json.writeValueAsString(values);
    }

    private static String requireText(String value, String field, int max) {
        String text = trimmed(value);
        if (text == null || text.length() > max) {
            throw fieldError(field, "is required and must be at most " + max + " characters");
        }
        return text;
    }

    private static long requirePrice(Long paise) {
        if (paise == null || paise < 0) {
            throw fieldError("price_paise", "must be zero or more");
        }
        return paise;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw fieldError(field, "must not be negative");
        }
        return value;
    }

    private static BigDecimal positiveOrNull(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() <= 0) {
            throw fieldError(field, "must be greater than zero");
        }
        return value;
    }

    private static int maxQty(Integer value) {
        if (value == null) {
            return 99;
        }
        if (value < 1 || BigDecimal.valueOf(value).compareTo(MAX_QTY) > 0) {
            throw fieldError("max_qty_per_order", "must be between 1 and 999");
        }
        return value;
    }

    private static StockUnit parseStockUnit(String value) {
        try {
            return StockUnit.valueOf(value.trim());
        } catch (RuntimeException e) {
            throw fieldError("stock_unit", "must be one of pc, kg, g, L, ml");
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }
}
