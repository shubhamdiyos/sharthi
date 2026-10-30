package com.saathi.merchant.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.FulfilmentCapability;
import com.saathi.merchant.domain.LicenceState;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.domain.MerchantState;
import com.saathi.merchant.repository.MerchantRepository;
import com.saathi.merchant.web.ShopViews;
import com.saathi.shared.audit.AuditEntry;
import com.saathi.shared.audit.AuditRecorder;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.TenantScope;
import com.saathi.tenancy.domain.Category;
import com.saathi.tenancy.domain.FulfilmentMode;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.domain.TenantCategory;
import com.saathi.tenancy.repository.CategoryRepository;
import com.saathi.tenancy.repository.ServiceWardRepository;
import com.saathi.tenancy.repository.TenantCategoryRepository;
import com.saathi.tenancy.service.ServiceabilityService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Merchant onboarding and lifecycle (§10.5.6, §10.5.8, PRD §15).
 *
 * <p>The one judgement this class exists to make is that approval is a <em>licence</em> decision,
 * not a state flip: a category that needs a licence cannot be approved while its evidence is
 * anything but {@code VALID}, and the platform's answer is {@code LICENCE_INCOMPLETE} rather than a
 * shop that starts trading on a photo nobody checked.
 *
 * <p>Every write records an audit row in the same transaction (§13.1). Approval, rejection,
 * suspension and visibility are the ⚑ actions of §13.2, so each carries a reason code.
 */
@Service
@RequiredArgsConstructor
public class MerchantOnboarding {

    private final MerchantRepository merchants;
    private final CategoryRepository categories;
    private final TenantCategoryRepository tenantCategories;
    private final ServiceWardRepository wards;
    private final ServiceabilityService serviceability;
    private final AuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    /** What a shop sends to apply. Public: an applicant has no account yet (PRD §15). */
    public record Application(
        String cityCode,
        Integer wardNo,
        String legalName,
        String tradeName,
        String categorySlug,
        String phone,
        String addressText,
        String licenceRef,
        String fulfilmentCapability,
        Boolean customerChoiceAllowed) {
    }

    /** The settings a merchant may change about itself (§10.5.6 {@code PATCH /m/settings}). */
    public record Settings(
        List<ShopViews.Hours> hours,
        String fulfilmentCapability,
        String defaultFulfilmentMode,
        Boolean customerChoiceAllowed,
        List<Integer> selfDeliverWards) {
    }

    @Transactional
    public Merchant apply(Application application, String requestId, String ipHash) {
        Tenant tenant = serviceability.liveTenant(application.cityCode());
        String legalName = required(application.legalName(), "legal_name", 2, 200);
        String tradeName = required(application.tradeName(), "trade_name", 2, 200);
        String phone = PhoneNormaliser.required(application.phone(), "phone");
        String address = required(application.addressText(), "address_text", 5, 400);
        if (application.categorySlug() == null || application.categorySlug().isBlank()) {
            throw fieldError("category_slug", "is required");
        }
        Category category = categories.findBySlug(application.categorySlug().trim().toLowerCase(Locale.ROOT))
            .filter(Category::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE,
                "That shop category is not available."));
        TenantCategory activation = tenantCategories.findByTenantIdAndCategoryId(tenant.getId(), category.getId())
            .filter(TenantCategory::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE,
                "That shop category is not active in this city."));

        ServiceWard ward = application.wardNo() == null ? null
            : serviceability.servedWard(tenant, application.wardNo());
        String code = slug(application.tradeName());
        merchants.findByTenantIdAndCode(tenant.getId(), code).ifPresent(existing -> {
            throw new ApiException(ErrorCode.DUPLICATE_SUBMISSION,
                "A shop with that name is already registered in this city.");
        });

        // A missing licence is not a refusal at application time — approval is where the
        // evidence is demanded (LICENCE_INCOMPLETE), and an applicant may still be arranging it.
        FulfilmentCapability capability = parseCapability(application.fulfilmentCapability());

        Merchant merchant = Merchant.builder()
            .id(Ulid.next(clock))
            .tenantId(tenant.getId())
            .code(code)
            .legalName(legalName)
            .tradeName(tradeName)
            .categoryId(category.getId())
            .phone(phone)
            .addressText(address)
            .wardId(ward == null ? null : ward.getId())
            .state(MerchantState.PENDING)
            .fulfilmentCapability(capability)
            .defaultFulfilmentMode(defaultMode(capability))
            .customerChoiceAllowed(application.customerChoiceAllowed() == null
                || application.customerChoiceAllowed())
            .licenceRef(application.licenceRef())
            .licenceState(application.licenceRef() == null || application.licenceRef().isBlank()
                ? LicenceState.NOT_SUBMITTED : LicenceState.SUBMITTED)
            .onboardingCompletedAt(clock.instant())
            .createdAt(clock.instant())
            .updatedAt(clock.instant())
            .build();
        merchants.save(merchant);

        audit.record(AuditEntry.builder()
            .tenantId(tenant.getId())
            // A public application has no principal: there is nobody to attribute it to until the
            // owner accepts an invitation. SYSTEM is the only actor the table allows without an id.
            .actorType(com.saathi.shared.tenant.PrincipalKind.SYSTEM)
            .action("MERCHANT_APPLIED")
            .entity("merchant")
            .entityId(merchant.getId())
            .afterJson(snapshot(merchant))
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
        return merchant;
    }

    /** §10.5.8 {@code POST /o/merchants/{code}/approve}. The licence gate lives here. */
    @Transactional
    public Merchant approve(AuthenticatedPrincipal caller, String code, String reason,
                            String requestId, String ipHash) {
        TenantScope scope = caller.requireScope();
        scope.require("MERCHANT_APPROVE");
        Merchant merchant = requireInTenant(code, caller.tenantId());
        if (merchant.getState() == MerchantState.APPROVED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "This shop is already approved.");
        }
        Category category = categories.findById(merchant.getCategoryId())
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE, "The shop category is gone."));
        tenantCategories.findByTenantIdAndCategoryId(merchant.getTenantId(), category.getId())
            .filter(TenantCategory::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_ACTIVE,
                "This shop's category is no longer active in the city."));
        if (category.isRequiresLicence() && merchant.getLicenceState() != LicenceState.VALID) {
            throw new ApiException(ErrorCode.LICENCE_INCOMPLETE,
                "This category needs a valid licence before the shop can be approved.");
        }

        String before = snapshot(merchant);
        MerchantState previous = merchant.getState();
        merchant.setState(MerchantState.APPROVED);
        merchant.setApprovedBy(caller.accountId());
        merchant.setApprovedAt(clock.instant());
        merchant.setRejectionReason(null);
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);

        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action("MERCHANT_APPROVED")
            .entity("merchant")
            .entityId(merchant.getId())
            .beforeJson(before)
            .afterJson(snapshot(merchant))
            .reasonCode(reason == null || reason.isBlank() ? "Operator approval" : reason)
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
        return merchant;
    }

    @Transactional
    public Merchant reject(AuthenticatedPrincipal caller, String code, String reason,
                           String requestId, String ipHash) {
        caller.requireScope().require("MERCHANT_APPROVE");
        Merchant merchant = requireInTenant(code, caller.tenantId());
        if (merchant.getState() != MerchantState.PENDING) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Only a pending application can be rejected.");
        }
        String before = snapshot(merchant);
        merchant.setState(MerchantState.REJECTED);
        merchant.setRejectionReason(reason.trim());
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);

        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action("MERCHANT_REJECTED")
            .entity("merchant")
            .entityId(merchant.getId())
            .beforeJson(before)
            .afterJson(snapshot(merchant))
            .reasonCode(reason.trim())
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
        return merchant;
    }

    @Transactional
    public Merchant suspend(AuthenticatedPrincipal caller, String code, String reason,
                            String requestId, String ipHash) {
        caller.requireScope().require("MERCHANT_SUSPEND");
        Merchant merchant = requireInTenant(code, caller.tenantId());
        if (merchant.getState() != MerchantState.APPROVED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Only an approved shop can be suspended.");
        }
        String before = snapshot(merchant);
        merchant.setState(MerchantState.SUSPENDED);
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);

        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action("MERCHANT_SUSPENDED")
            .entity("merchant")
            .entityId(merchant.getId())
            .beforeJson(before)
            .afterJson(snapshot(merchant))
            .reasonCode(reason.trim())
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
        return merchant;
    }

    /**
     * PRD §5.4's platform decision: only {@code SHOP_VISIBILITY_WRITE} (super admin) hides a shop.
     * An operator raising a concern opens a ticket instead, and the ticket is what a review reads.
     */
    @Transactional
    public Merchant hide(AuthenticatedPrincipal caller, String code, String reason,
                         String requestId, String ipHash) {
        caller.require("SHOP_VISIBILITY_WRITE");
        Merchant merchant = requireInTenant(code, caller.tenantId());
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A hidden shop needs a reason.");
        }
        String before = snapshot(merchant);
        merchant.setHiddenReason(reason.trim());
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);
        auditVisibility(caller, merchant, before, requestId, ipHash, reason.trim());
        return merchant;
    }

    @Transactional
    public Merchant unhide(AuthenticatedPrincipal caller, String code, String reason,
                           String requestId, String ipHash) {
        caller.require("SHOP_VISIBILITY_WRITE");
        Merchant merchant = requireInTenant(code, caller.tenantId());
        String before = snapshot(merchant);
        merchant.setHiddenReason(null);
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);
        auditVisibility(caller, merchant, before, requestId, ipHash,
            reason == null || reason.isBlank() ? "Shop restored to search" : reason.trim());
        return merchant;
    }

    @Transactional
    public Merchant updateSettings(AuthenticatedPrincipal caller, String code, Settings settings,
                                   String requestId, String ipHash) {
        caller.require("SHOP_SETTINGS_WRITE");
        Merchant merchant = requireOwned(caller, code);
        String before = snapshot(merchant);

        FulfilmentCapability capability = settings.fulfilmentCapability() == null
            ? merchant.getFulfilmentCapability()
            : parseCapability(settings.fulfilmentCapability());
        FulfilmentMode mode = settings.defaultFulfilmentMode() == null
            ? merchant.getDefaultFulfilmentMode()
            : MerchantDirectory.parseMode(settings.defaultFulfilmentMode());
        boolean coherent = switch (capability) {
            case BOTH -> true;
            case PLATFORM_ONLY -> mode == FulfilmentMode.PLATFORM;
            case MERCHANT_ONLY -> mode == FulfilmentMode.MERCHANT;
        };
        if (!coherent) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "The default fulfilment mode must be one this shop can deliver.");
        }
        boolean modeChanged = capability != merchant.getFulfilmentCapability()
            || mode != merchant.getDefaultFulfilmentMode()
            || (settings.customerChoiceAllowed() != null
                && settings.customerChoiceAllowed() != merchant.isCustomerChoiceAllowed());

        merchant.setFulfilmentCapability(capability);
        merchant.setDefaultFulfilmentMode(mode);
        if (settings.customerChoiceAllowed() != null) {
            merchant.setCustomerChoiceAllowed(settings.customerChoiceAllowed());
        }
        if (settings.hours() != null) {
            validateHours(settings.hours());
            merchant.setOperatingHoursJson(json.writeValueAsString(settings.hours()));
        }
        if (settings.selfDeliverWards() != null) {
            merchant.setSelfDeliverZonesJson(json.writeValueAsString(settings.selfDeliverWards()));
        }
        merchant.setUpdatedAt(clock.instant());
        merchants.save(merchant);

        if (modeChanged) {
            audit.record(AuditEntry.builder()
                .tenantId(merchant.getTenantId())
                .actorType(caller.kind())
                .actorId(caller.accountId())
                .action("FULFILMENT_MODE_CONFIG_CHANGED")
                .entity("merchant")
                .entityId(merchant.getId())
                .beforeJson(before)
                .afterJson(snapshot(merchant))
                .reasonCode("Merchant changed fulfilment settings")
                .requestId(requestId)
                .ipHash(ipHash)
                .occurredAt(clock.instant())
                .build());
        }
        return merchant;
    }

    /** A shop staffed by the caller, or 404 — row scope is answered as absence (§11.3-4). */
    @Transactional(readOnly = true)
    public Merchant requireOwned(AuthenticatedPrincipal caller, String code) {
        TenantScope scope = caller.requireScope();
        Merchant merchant = merchants.findByTenantIdAndCode(scope.tenantId(), code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That shop is not in your account."));
        if (caller.merchantId() == null || !caller.merchantId().equals(merchant.getId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "That shop is not in your account.");
        }
        return merchant;
    }

    /**
     * The shop a session is bound to (§12.4), for the {@code /m/*} endpoints whose path has no
     * shop code. A session with no merchant scope answers 404 rather than guessing a shop: the
     * guess would be a write against a shop the caller never chose.
     */
    @Transactional(readOnly = true)
    public Merchant ownMerchant(AuthenticatedPrincipal caller) {
        TenantScope scope = caller.requireScope();
        if (caller.merchantId() == null || caller.merchantId().isBlank()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "This session is not bound to a shop.");
        }
        return merchants.findByIdAndTenantId(caller.merchantId(), scope.tenantId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                "This session is not bound to a shop."));
    }

    /** A shop inside the caller's own city; another city's shop is absence, not refusal (§4.3-5). */
    @Transactional(readOnly = true)
    public Merchant requireInTenant(String code, String callerTenantId) {
        return merchants.findByTenantIdAndCode(callerTenantId, code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that shop."));
    }

    private void auditVisibility(AuthenticatedPrincipal caller, Merchant merchant, String before,
                                 String requestId, String ipHash, String reason) {
        audit.record(AuditEntry.builder()
            .tenantId(merchant.getTenantId())
            .actorType(caller.kind())
            .actorId(caller.accountId())
            .action("SHOP_VISIBILITY_CHANGED")
            .entity("merchant")
            .entityId(merchant.getId())
            .beforeJson(before)
            .afterJson(snapshot(merchant))
            .reasonCode(reason)
            .requestId(requestId)
            .ipHash(ipHash)
            .occurredAt(clock.instant())
            .build());
    }

    public String snapshot(Merchant merchant) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", merchant.getCode());
        values.put("state", merchant.getState().name());
        values.put("hidden_reason", merchant.getHiddenReason());
        values.put("licence_state", merchant.getLicenceState().name());
        values.put("fulfilment_capability", merchant.getFulfilmentCapability().name());
        values.put("default_fulfilment_mode", merchant.getDefaultFulfilmentMode().name());
        values.put("customer_choice_allowed", merchant.isCustomerChoiceAllowed());
        return json.writeValueAsString(values);
    }

    /** One line per ISO weekday, each time {@code HH:mm}; the storefront prints these verbatim. */
    private static void validateHours(List<ShopViews.Hours> hours) {
        if (hours.size() > 7) {
            throw fieldError("hours", "has at most seven rows, one per day");
        }
        java.util.Set<Integer> days = new java.util.HashSet<>();
        for (ShopViews.Hours line : hours) {
            if (line.day() < 1 || line.day() > 7 || !days.add(line.day())) {
                throw fieldError("hours", "each row needs a distinct ISO day from 1 to 7");
            }
            if (!line.closed()) {
                requireTime(line.opensAt(), "hours");
                requireTime(line.closesAt(), "hours");
            }
        }
    }

    private static void requireTime(String value, String field) {
        if (value == null || !value.matches("^([01][0-9]|2[0-3]):[0-5][0-9]$")) {
            throw fieldError(field, "times are HH:mm in the city's own clock");
        }
    }

    private static String required(String value, String field, int min, int max) {
        String text = value == null ? null : value.trim();
        if (text == null || text.length() < min || text.length() > max) {
            throw fieldError(field, "must be " + min + " to " + max + " characters");
        }
        return text;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
            Map.of("field_errors", List.of(Map.of("field", field, "message", message))));
    }

    private static FulfilmentCapability parseCapability(String value) {
        if (value == null || value.isBlank()) {
            return FulfilmentCapability.PLATFORM_ONLY;
        }
        try {
            return FulfilmentCapability.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "fulfilment_capability must be PLATFORM_ONLY, MERCHANT_ONLY or BOTH.");
        }
    }

    private static FulfilmentMode defaultMode(FulfilmentCapability capability) {
        return capability == FulfilmentCapability.MERCHANT_ONLY
            ? FulfilmentMode.MERCHANT : FulfilmentMode.PLATFORM;
    }

    /** A shop's public code, derived from its name and stable for the life of the shop. */
    static String slug(String text) {
        String slug = text.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (slug.length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The shop name needs at least three letters.");
        }
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-$", "") : slug;
    }
}
