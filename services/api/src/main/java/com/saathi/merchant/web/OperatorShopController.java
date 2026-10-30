package com.saathi.merchant.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.merchant.service.MerchantOnboarding;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.8's shop surface: the operator's directory and the decisions an operator may take about a
 * shop. Hiding is not one of them — PRD §5.4 makes visibility a platform decision, so
 * {@code SHOP_VISIBILITY_WRITE} sits outside the operator's role set in V4 and an operator's
 * remedy is a {@code SHOP_FLAG} ticket (that endpoint arrives with the support module).
 *
 * <p>Suspension is audited and permission-checked but not step-up challenged: §12.3's step-up flow
 * is not built yet, and saying so here is better than a comment in a controller that reads as if
 * the control were in place. It is on the module's open-items list.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OperatorShopController {

    private final MerchantDirectory directory;
    private final MerchantOnboarding onboarding;
    private final RequestTags tags;

    public record ReasonBody(String reason) {
    }

    public record VisibilityBody(Boolean hidden, String reason) {
    }

    @GetMapping("/o/shops")
    public Page<ShopViews.Admin> shops(
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "ward_no", required = false) Integer wardNo,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit,
        AuthenticatedPrincipal caller) {
        caller.require("MERCHANT_READ_TENANT");
        return directory.operatorShops(caller.requireScope().tenantId(), state, wardNo, category, q,
            cursor, limit);
    }

    @PostMapping("/o/merchants/{code}/approve")
    public ShopViews.Admin approve(@PathVariable("code") String code,
                                   @RequestBody(required = false) ReasonBody body,
                                   AuthenticatedPrincipal caller,
                                   HttpServletRequest request) {
        Merchant merchant = onboarding.approve(caller, code,
            body == null ? null : body.reason(),
            MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash());
        return directory.adminOf(merchant);
    }

    /**
     * §10.5.8 makes the reason mandatory and shows it to the applicant: a rejection a shop cannot
     * read is a support ticket waiting to be opened, and the operator has already answered it once.
     */
    @PostMapping("/o/merchants/{code}/reject")
    public ShopViews.Admin reject(@PathVariable("code") String code,
                                  @Valid @RequestBody ReasonBody body,
                                  AuthenticatedPrincipal caller,
                                  HttpServletRequest request) {
        if (body == null || body.reason() == null || body.reason().trim().length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "reason",
                    "message", "is required and must be at least 3 characters"))));
        }
        Merchant merchant = onboarding.reject(caller, code, body.reason(),
            MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash());
        return directory.adminOf(merchant);
    }

    @PostMapping("/o/merchants/{code}/suspend")
    public ShopViews.Admin suspend(@PathVariable("code") String code,
                                   @Valid @RequestBody ReasonBody body,
                                   AuthenticatedPrincipal caller,
                                   HttpServletRequest request) {
        if (body == null || body.reason() == null || body.reason().trim().length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "reason",
                    "message", "is required and must be at least 3 characters"))));
        }
        Merchant merchant = onboarding.suspend(caller, code, body.reason(),
            MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash());
        return directory.adminOf(merchant);
    }

    /**
     * Hide or unhide — the one platform decision in this controller. A super admin reaches it
     * inside a tenant scope (§4.4), which is why the path is {@code /o/} and not {@code /a/}: the
     * screen's flow is a tenant-access grant followed by the operator's shop list.
     */
    @PostMapping("/o/shops/{code}/visibility")
    public ShopViews.Admin visibility(@PathVariable("code") String code,
                                      @Valid @RequestBody VisibilityBody body,
                                      AuthenticatedPrincipal caller,
                                      HttpServletRequest request) {
        if (body == null || body.hidden() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "hidden", "message", "is required"))));
        }
        Merchant merchant = body.hidden()
            ? onboarding.hide(caller, code, body.reason(),
                MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash())
            : onboarding.unhide(caller, code, body.reason(),
                MDC.get(RequestIdFilter.MDC_KEY), tags.of(request).ipHash());
        return directory.adminOf(merchant);
    }
}
