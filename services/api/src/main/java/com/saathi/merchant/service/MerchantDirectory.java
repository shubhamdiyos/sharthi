package com.saathi.merchant.service;

import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.domain.MerchantState;
import com.saathi.merchant.repository.MerchantRepository;
import com.saathi.merchant.web.ShopViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.repository.ServiceWardRepository;
import com.saathi.tenancy.service.ServiceabilityService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The storefront's read side (§10.5.3) and the operator's shop directory (§10.5.8).
 *
 * <p>Visibility is decided here and only here: {@code APPROVED}, not hidden, inside a {@code LIVE}
 * tenant, and — for the ward-filtered list — serving the ward the customer chose (PRD §8.1, §8.2).
 * A shop that fails any of those answers {@code NOT_FOUND}, never "this shop exists but you may not
 * see it", because §4.3-5 treats visibility as scope.
 */
@Service
@RequiredArgsConstructor
public class MerchantDirectory {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MerchantRepository merchants;
    private final ShopProductCounts productCounts;
    private final ServiceabilityService serviceability;
    private final ServiceWardRepository wards;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * {@code GET /shops}: the storefront list. {@code ward_no} is optional so the home screen can
     * show "all shops in Siwan" before a ward is picked; when it is present it filters, and an
     * inactive ward is refused rather than silently returning an empty page.
     */
    @Transactional(readOnly = true)
    public Page<ShopViews.Summary> shops(String cityCode, Integer wardNo, String categorySlug,
                                         String q, String cursor, Integer limit) {
        Tenant tenant = serviceability.liveTenant(cityCode);
        ServiceWard ward = wardNo == null ? null : serviceability.servedWard(tenant, wardNo);
        int size = CursorPager.clampLimit(limit);
        List<String> keys = CursorPager.decode(cursor);
        String afterName = keys.size() > 0 ? keys.get(0) : "";
        String afterId = keys.size() > 1 ? keys.get(1) : "";
        String query = q == null ? "" : q.trim();
        String categoryId = serviceability.categoryIdOf(tenant, categorySlug);

        List<Merchant> rows = merchants.storefront(tenant.getId(), MerchantState.APPROVED,
            ward == null ? "" : ward.getId(), categoryId, query,
            query.isEmpty() ? null : "%" + query.toLowerCase() + "%",
            afterName, afterId, PageRequest.of(0, CursorPager.fetchSize(size)));

        boolean hasMore = rows.size() > size;
        List<Merchant> page = hasMore ? rows.subList(0, size) : rows;
        Map<String, String[]> labels = labelsOf(tenant, page);
        List<ShopViews.Summary> data = page.stream()
            .map(m -> summary(tenant, m, labels, ward))
            .toList();
        String next = hasMore
            ? CursorPager.encode(List.of(page.get(page.size() - 1).getTradeName(),
                page.get(page.size() - 1).getId()))
            : null;
        return Page.of(data, next, hasMore, size);
    }

    /**
     * {@code GET /shops/{code}}. A code is unique inside a city, so the caller may name the city;
     * without it the code is resolved across live cities and must be unambiguous. That is the
     * honest handling of a detail URL that §10.5.3 shows without a city — inventing one city's
     * answer would let a Siwan customer open a Mashaun shop.
     */
    @Transactional(readOnly = true)
    public ShopViews.Detail detail(String cityCode, String code) {
        Merchant merchant = visibleShop(cityCode, code);
        return detailOf(merchant);
    }

    @Transactional(readOnly = true)
    public ShopViews.Detail detailOf(Merchant merchant) {
        Tenant tenant = serviceability.liveTenantById(merchant.getTenantId());
        ServiceWard ward = merchant.getWardId() == null ? null
            : wards.findById(merchant.getWardId()).orElse(null);
        Map<String, String[]> labels = labelsOf(tenant, List.of(merchant));
        return new ShopViews.Detail(
            merchant.getCode(),
            merchant.getTradeName(),
            merchant.getLegalName(),
            labels.get(merchant.getCategoryId())[0],
            labels.get(merchant.getCategoryId())[1],
            ward == null ? null : ward.getWardNo(),
            ward == null ? null : ward.getName(),
            merchant.getAddressText(),
            merchant.getPhone(),
            closingAt(merchant),
            hoursOf(merchant),
            ShopViews.options(merchant),
            merchant.getDefaultFulfilmentMode().name(),
            merchant.isCustomerChoiceAllowed(),
            ShopViews.zero(),
            merchant.getLicenceState() == com.saathi.merchant.domain.LicenceState.VALID,
            productCounts.activeProductsOf(merchant.getId()));
    }

    /** The shop behind a public code, or {@code NOT_FOUND} — the one visibility decision. */
    @Transactional(readOnly = true)
    public Merchant visibleShop(String cityCode, String code) {
        String shopCode = code == null ? "" : code.trim();
        if (cityCode != null && !cityCode.isBlank()) {
            Tenant tenant = serviceability.liveTenant(cityCode);
            return requireVisible(tenant.getId(), shopCode);
        }
        List<Merchant> matches = new ArrayList<>();
        for (Tenant tenant : serviceability.liveCities()) {
            merchants.findByTenantIdAndCode(tenant.getId(), shopCode)
                .filter(Merchant::isVisible)
                .ifPresent(matches::add);
        }
        if (matches.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that shop.");
        }
        if (matches.size() > 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "This shop code exists in more than one city; pass city_code.");
        }
        return matches.get(0);
    }

    @Transactional(readOnly = true)
    public Merchant requireVisible(String tenantId, String code) {
        Merchant merchant = merchants.findByTenantIdAndCode(tenantId, code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that shop."));
        if (!merchant.isVisible()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that shop.");
        }
        return merchant;
    }

    /**
     * A visible shop by internal id, for {@code catalog/} resolving a product's owner. Empty means
     * the same thing as {@link #requireVisible} refusing: the row is not there for this reader.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Merchant> visibleMerchantById(String tenantId, String merchantId) {
        return merchants.findByIdAndTenantId(merchantId, tenantId).filter(Merchant::isVisible);
    }

    /**
     * The shops a search may return. {@code catalog/} asks with this instead of joining
     * {@code merchant}, which is the call that keeps §3's boundary intact.
     */
    @Transactional(readOnly = true)
    public List<String> visibleMerchantIds(String tenantId, String wardId) {
        return merchants.storefront(tenantId, MerchantState.APPROVED, wardId == null ? "" : wardId,
                "", "", null, "", "", PageRequest.of(0, CursorPager.MAX_LIMIT))
            .stream().map(Merchant::getId).distinct().toList();
    }

    /**
     * {@code GET /o/shops}: the operator's directory (§10.5.8). Unlike the storefront it includes
     * pending, suspended and hidden shops, because judging and repairing them is the screen's
     * entire job.
     */
    @Transactional(readOnly = true)
    public Page<ShopViews.Admin> operatorShops(String tenantId, String state, Integer wardNo,
                                               String categorySlug, String q, String cursor,
                                               Integer limit) {
        Tenant tenant = serviceability.tenantById(tenantId);
        ServiceWard ward = wardNo == null ? null : serviceability.servedWard(tenant, wardNo);
        MerchantState stateFilter = parseState(state);
        int size = CursorPager.clampLimit(limit);
        List<String> keys = CursorPager.decode(cursor);
        String afterName = keys.size() > 0 ? keys.get(0) : "";
        String afterId = keys.size() > 1 ? keys.get(1) : "";
        String query = q == null ? "" : q.trim();
        String categoryId = serviceability.categoryIdOf(tenant, categorySlug);

        List<Merchant> rows = merchants.directory(tenantId, stateFilter,
            ward == null ? "" : ward.getId(), categoryId, query,
            query.isEmpty() ? null : "%" + query.toLowerCase() + "%",
            afterName, afterId, PageRequest.of(0, CursorPager.fetchSize(size)));
        boolean hasMore = rows.size() > size;
        List<Merchant> page = hasMore ? rows.subList(0, size) : rows;
        List<ShopViews.Admin> data = page.stream()
            .map(m -> admin(m, tenant, ward))
            .toList();
        String next = hasMore
            ? CursorPager.encode(List.of(page.get(page.size() - 1).getTradeName(),
                page.get(page.size() - 1).getId()))
            : null;
        return Page.of(data, next, hasMore, size);
    }

    @Transactional(readOnly = true)
    public ShopViews.Admin adminOf(Merchant merchant) {
        return admin(merchant, serviceability.tenantById(merchant.getTenantId()), null);
    }

    private ShopViews.Admin admin(Merchant merchant, Tenant tenant, ServiceWard filteredWard) {
        Map<String, String[]> labels = labelsOf(tenant, List.of(merchant));
        ServiceWard ward = filteredWard != null ? filteredWard
            : merchant.getWardId() == null ? null : wards.findById(merchant.getWardId()).orElse(null);
        return new ShopViews.Admin(
            merchant.getCode(),
            tenant.getCode(),
            merchant.getLegalName(),
            merchant.getTradeName(),
            labels.get(merchant.getCategoryId())[0],
            labels.get(merchant.getCategoryId())[1],
            ward == null ? null : ward.getWardNo(),
            ward == null ? null : ward.getName(),
            merchant.getPhone(),
            merchant.getState().name(),
            merchant.getHiddenReason(),
            merchant.getLicenceRef(),
            merchant.getLicenceState().name(),
            merchant.getFulfilmentCapability().name(),
            merchant.getDefaultFulfilmentMode().name(),
            merchant.isCustomerChoiceAllowed(),
            productCounts.activeProductsOf(merchant.getId()));
    }

    private ShopViews.Summary summary(Tenant tenant, Merchant merchant, Map<String, String[]> labels,
                                      ServiceWard filteredWard) {
        ServiceWard ward = filteredWard != null ? filteredWard
            : merchant.getWardId() == null ? null : wards.findById(merchant.getWardId()).orElse(null);
        String[] label = labels.get(merchant.getCategoryId());
        return ShopViews.Summary.of(merchant, label[0], label[1],
            ward == null ? null : ward.getWardNo(), ward == null ? null : ward.getName(),
            closingAt(merchant));
    }

    /** categoryId → {slug, displayName} for a page of shops, in one pass. */
    private Map<String, String[]> labelsOf(Tenant tenant, List<Merchant> page) {
        Map<String, String[]> labels = new HashMap<>();
        for (var category : serviceability.activatedCategories(tenant)) {
            labels.put(category.category().getId(),
                new String[] {category.slug(), category.displayName()});
        }
        for (Merchant merchant : page) {
            labels.computeIfAbsent(merchant.getCategoryId(), id -> new String[] {"unknown", "Other"});
        }
        return labels;
    }

    /** The shop's hours, for the settings screen's read-back. Malformed storage reads as empty. */
    public List<ShopViews.Hours> hoursOf(Merchant merchant) {
        if (merchant.getOperatingHoursJson() == null || merchant.getOperatingHoursJson().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(merchant.getOperatingHoursJson(),
                new TypeReference<List<ShopViews.Hours>>() { });
        } catch (RuntimeException e) {
            // Hours are display data. A malformed row shows no hours rather than taking the shop
            // down; the settings write validates the shape before storing it.
            return List.of();
        }
    }

    /** The wards this shop delivers to itself (§6.2), read back for the settings screen. */
    public List<Integer> selfDeliverWardsOf(Merchant merchant) {
        if (merchant.getSelfDeliverZonesJson() == null || merchant.getSelfDeliverZonesJson().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(merchant.getSelfDeliverZonesJson(),
                new TypeReference<List<Integer>>() { });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The closing time for the city's own clock, not the server's. The pilot runs in India, and a
     * shop open "until 22:00" is closed at 22:05 IST whatever a UTC server thinks.
     */
    private String closingAt(Merchant merchant) {
        List<ShopViews.Hours> hours = hoursOf(merchant);
        if (hours.isEmpty()) {
            return null;
        }
        DayOfWeek day = clock.instant().atZone(IST).getDayOfWeek();
        return hours.stream()
            .filter(h -> h.day() == day.getValue() && !h.closed())
            .map(ShopViews.Hours::closesAt)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /** Exposed so the shop settings endpoint can translate its mode input without a second copy. */
    public static FulfilmentMode parseMode(String value) {
        try {
            return FulfilmentMode.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown fulfilment mode.");
        }
    }

    /** An absent state means "every state" on the operator directory, never "pending only". */
    private static MerchantState parseState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MerchantState.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "state must be PENDING, APPROVED, SUSPENDED or REJECTED.");
        }
    }
}
