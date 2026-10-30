package com.saathi.tenancy.service;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.tenancy.domain.Category;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.domain.TenantCategory;
import com.saathi.tenancy.domain.TenantStatus;
import com.saathi.tenancy.domain.WardFeeRule;
import com.saathi.tenancy.repository.CategoryRepository;
import com.saathi.tenancy.repository.ServiceWardRepository;
import com.saathi.tenancy.repository.TenantCategoryRepository;
import com.saathi.tenancy.repository.TenantRepository;
import com.saathi.tenancy.repository.WardFeeRuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Can this address be served, and what would it cost" (§10.5.2), which is the question PRD §8.1
 * asks before a storefront is allowed to show a cart.
 *
 * <p>A city is addressed by {@code code} and a ward by its number inside that city — never by an
 * internal id (§4.3-6). The lookup order is the order the refusals make sense in: an unknown city
 * cannot be a closed city, and a city that is closed cannot be asked whether it serves a ward.
 *
 * <p>Both non-{@code LIVE} refusals answer plainly rather than with {@code NOT_FOUND}. §4.3-5's
 * rule is about another <em>tenant's</em> rows, and a city the platform has announced is one the
 * whole country already knows about; telling a customer in Mashaun that Mashaun does not exist is
 * not a privacy win, it is a wrong answer they will retry.
 */
@Service
@RequiredArgsConstructor
public class ServiceabilityService {

    private final TenantRepository tenants;
    private final ServiceWardRepository wards;
    private final WardFeeRuleRepository feeRules;
    private final CategoryRepository categories;
    private final TenantCategoryRepository tenantCategories;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Tenant> liveCities() {
        return tenants.findByStatusOrderByCodeAsc(TenantStatus.LIVE);
    }

    /**
     * The city behind a public {@code city_code}, refusing a name nobody serves and a city that is
     * not open. Available to other modules because {@code PUT /me/ward} resolves the same pair with
     * the same rules — a customer's stored ward and a checked ward must never disagree about which
     * cities exist.
     */
    @Transactional(readOnly = true)
    public Tenant liveTenant(String cityCode) {
        String code = cityCode == null ? "" : cityCode.trim();
        Tenant tenant = tenants.findByCodeIgnoreCase(code).orElseThrow(() -> new ApiException(
            ErrorCode.OUTSIDE_SERVICE_AREA, "We do not deliver in that city yet."));
        if (tenant.getStatus() != TenantStatus.LIVE) {
            throw new ApiException(ErrorCode.TENANT_NOT_LIVE, "That city is not taking orders right now.");
        }
        return tenant;
    }

    /**
     * A city resolved from a row's own {@code tenant_id}, refusing one that is not live. Internal
     * callers (a shop's own detail, a product's shop) already hold the id and must not be given a
     * second way to resolve a city that skips the status check.
     */
    @Transactional(readOnly = true)
    public Tenant liveTenantById(String tenantId) {
        Tenant tenant = tenantById(tenantId);
        if (tenant.getStatus() != TenantStatus.LIVE) {
            throw new ApiException(ErrorCode.TENANT_NOT_LIVE, "That city is not taking orders right now.");
        }
        return tenant;
    }

    /**
     * A city by internal id, whatever its status. Staff surfaces read this: an operator in a
     * draft or suspended city still has a queue to look at, and the storefront is the only caller
     * that must be refused a city which is not live.
     */
    @Transactional(readOnly = true)
    public Tenant tenantById(String tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new ApiException(
            ErrorCode.OUTSIDE_SERVICE_AREA, "We do not deliver in that city yet."));
    }

    /** A ward that is switched on in this city. An inactive ward is not served, and says so. */
    @Transactional(readOnly = true)
    public ServiceWard servedWard(Tenant tenant, int wardNo) {
        ServiceWard ward = wards.findByTenantIdAndWardNo(tenant.getId(), wardNo).orElse(null);
        if (ward == null || !ward.isActive()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "We do not deliver to that area of the city yet.");
        }
        return ward;
    }

    @Transactional(readOnly = true)
    public List<ServiceWard> activeWards(Tenant tenant) {
        return wards.findByTenantIdAndActiveTrueOrderBySortAscWardNoAsc(tenant.getId());
    }

    /**
     * The ward a session is already bound to, refusing one this city has since switched off.
     *
     * <p>The session stores an id (that is how {@code PUT /me/ward} remembers the choice), but a
     * cart must re-check it against the rows: a ward deactivated after the choice was made is not
     * served, and a cart priced against it would quote a fee the city no longer charges.
     */
    @Transactional(readOnly = true)
    public ServiceWard wardById(Tenant tenant, String wardId) {
        ServiceWard ward = wardId == null ? null : wards.findById(wardId).orElse(null);
        if (ward == null || !tenant.getId().equals(ward.getTenantId()) || !ward.isActive()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "We do not deliver to that area of the city yet.");
        }
        return ward;
    }

    /**
     * The rules in force for one ward, at most one per fulfilment mode.
     *
     * <p>"Newest {@code valid_from} that has started" and not "the row in the table": a ward has both
     * its old rate and its replacement, and §9.7's money identity test recomputes an order's fee with
     * this same rule years later. Two rows for one mode at one instant is what the unique key
     * prevents, so {@code putIfAbsent} over an ordered list is the whole of the tie-break.
     */
    @Transactional(readOnly = true)
    public List<WardFeeRule> effectiveFees(Tenant tenant, ServiceWard ward) {
        Instant now = clock.instant();
        Map<FulfilmentMode, WardFeeRule> perMode = new EnumMap<>(FulfilmentMode.class);
        for (WardFeeRule rule : feeRules.findEffectiveAt(tenant.getId(), ward.getId(), now)) {
            perMode.putIfAbsent(rule.getFulfilmentMode(), rule);
        }
        List<WardFeeRule> ordered = new ArrayList<>(perMode.values());
        ordered.sort(Comparator.comparing(WardFeeRule::getFulfilmentMode));
        return List.copyOf(ordered);
    }

    /**
     * This city's slice of the global taxonomy: activated, switched on at both ends, in the city's
     * own order and with the city's own name for it (§4.1).
     */
    @Transactional(readOnly = true)
    public List<ActivatedCategory> activatedCategories(Tenant tenant) {
        List<TenantCategory> activations = tenantCategories.findByTenantIdAndActiveTrue(tenant.getId());
        if (activations.isEmpty()) {
            return List.of();
        }
        List<String> ids = activations.stream().map(TenantCategory::getCategoryId).distinct().toList();
        Map<String, Category> global = new HashMap<>();
        for (Category category : categories.findByIdInAndActiveTrue(ids)) {
            global.put(category.getId(), category);
        }
        List<ActivatedCategory> views = new ArrayList<>();
        for (TenantCategory activation : activations) {
            Category category = global.get(activation.getCategoryId());
            // A city that activated a node the platform later retired keeps the activation row and
            // shows no category for it. Deleting the activation would be editing history (§13.3).
            if (category != null) {
                views.add(new ActivatedCategory(category, activation));
            }
        }
        views.sort(Comparator.comparingInt(ActivatedCategory::sort));
        return List.copyOf(views);
    }

    /**
     * One activated taxonomy node's global id, by the public slug. The storefront and the
     * merchant's own catalogue both narrow by {@code ?category=}, and both must refuse a slug
     * this city has not switched on rather than return an empty page that looks like a bug.
     */
    @Transactional(readOnly = true)
    public String categoryIdOf(Tenant tenant, String slug) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        return activatedCategories(tenant).stream()
            .filter(c -> c.slug().equalsIgnoreCase(slug.trim()))
            .map(c -> c.category().getId())
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                "That category is not active in this city."));
    }

    /**
     * One taxonomy node as this city presents it. The display name and the order are the city's;
     * the slug and the licence requirement are the platform's and cannot be overridden (§4.1).
     */
    public record ActivatedCategory(Category category, TenantCategory activation) {

        public String slug() {
            return category.getSlug();
        }

        /** The city's rename if it has one, otherwise the global name. */
        public String displayName() {
            String local = activation.getDisplayName();
            return local == null || local.isBlank() ? category.getName() : local;
        }

        public boolean requiresLicence() {
            return category.isRequiresLicence();
        }

        public int sort() {
            Integer override = activation.getSortOverride();
            return override != null ? override : category.getSort();
        }
    }
}
