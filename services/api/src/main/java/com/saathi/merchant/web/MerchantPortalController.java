package com.saathi.merchant.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.merchant.service.MerchantOnboarding;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.6's two merchant-portal endpoints that are about the shop rather than its goods.
 *
 * <p>{@code POST /m/onboarding} is public on purpose: an applicant has no account yet, and
 * requiring one would mean a login created for an unapproved shop. The owner invitation §12.3
 * describes is not built; when it is, it attaches to the {@code PENDING} row this endpoint writes.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MerchantPortalController {

    private final MerchantOnboarding onboarding;
    private final MerchantDirectory directory;
    private final RequestTags tags;

    /** What the applicant needs to know: the id, the public code, and that they are not live yet. */
    public record ApplicationResult(String applicationId, String code, String state) {
    }

    /** {@code PATCH /m/settings}'s answer: what the shop now says about itself. */
    public record ShopSettingsView(
        String code,
        String tradeName,
        List<ShopViews.Hours> hours,
        String fulfilmentCapability,
        String defaultFulfilmentMode,
        boolean customerChoiceAllowed,
        List<Integer> selfDeliverWards,
        Instant updatedAt) {
    }

    @PostMapping("/m/onboarding")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResult apply(@Valid @RequestBody MerchantOnboarding.Application body,
                                   HttpServletRequest request) {
        Merchant merchant = onboarding.apply(body, MDC.get(RequestIdFilter.MDC_KEY),
            tags.of(request).ipHash());
        return new ApplicationResult(merchant.getId(), merchant.getCode(), merchant.getState().name());
    }

    @PatchMapping("/m/settings")
    public ShopSettingsView settings(@Valid @RequestBody MerchantOnboarding.Settings body,
                                     AuthenticatedPrincipal caller,
                                     HttpServletRequest request) {
        Merchant own = onboarding.ownMerchant(caller);
        Merchant merchant = onboarding.updateSettings(caller, own.getCode(), body,
            MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash());
        return new ShopSettingsView(
            merchant.getCode(),
            merchant.getTradeName(),
            directory.hoursOf(merchant),
            merchant.getFulfilmentCapability().name(),
            merchant.getDefaultFulfilmentMode().name(),
            merchant.isCustomerChoiceAllowed(),
            directory.selfDeliverWardsOf(merchant),
            merchant.getUpdatedAt());
    }
}
