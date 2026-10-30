package com.saathi.tenancy.web;

import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.FeatureFlagReader;
import com.saathi.tenancy.service.ServiceabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.2's public geography surface: which cities exist, which wards of one are served, what
 * serving them costs, and whether this address is served at all.
 *
 * <p>All four are unauthenticated. PRD §8.1's order — city, then ward, then shops, then cart — runs
 * before a customer has any reason to sign in, and a storefront that needs a session to learn
 * whether it delivers to your street loses the customer at the first screen.
 *
 * <p>No {@code @Validated} on the type: since Framework 6.1 a controller's own parameter constraints
 * are validated by the built-in handler-method mechanism, and putting the annotation back routes the
 * failure through the AOP proxy instead, which produces a different exception and a different 422
 * body from the one {@code HttpContractIT} proves every other endpoint returns.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GeoController {

    private final ServiceabilityService serviceability;
    private final FeatureFlagReader featureFlags;

    /**
     * §10.5.2 declares this as {@code CitySummary[]} and not a page. The pilot's city list is bounded
     * by §1's own scope, and §10.3's cursor rules buy nothing on a list this short — so the array the
     * table asks for is what is returned, and the collision with "every collection endpoint is a
     * page" is raised for the founder rather than resolved silently against the row that names it.
     */
    @GetMapping("/cities")
    public List<CitySummary> cities() {
        return serviceability.liveCities().stream().map(CitySummary::of).toList();
    }

    @GetMapping("/geo/wards")
    public List<WardSummary> wards(
        @RequestParam(name = "city_code", required = false) @NotBlank @Size(max = 32) String cityCode) {
        Tenant tenant = serviceability.liveTenant(cityCode);
        return serviceability.activeWards(tenant).stream().map(WardSummary::of).toList();
    }

    /**
     * The serviceability gate (PRD §8.1): it resolves a city and a ward and answers with the fees a
     * quote would use, so a storefront that shows a cart has already shown the truth about what
     * serving that cart costs.
     *
     * <p>{@code pincode} and {@code address} are accepted and unused. §10.5.2 lists them, this build
     * has no geocoder, and a ward is the unit of serviceability (D8), so neither free-text field can
     * change the answer. They stay in the contract so a client that follows the document is not told
     * its request is invalid — and they are never echoed back, because an address that comes in as
     * text should not leave as one.
     */
    @PostMapping("/geo/check")
    public ServiceabilityView check(@Valid @RequestBody CheckRequest request) {
        Tenant tenant = serviceability.liveTenant(request.cityCode());
        ServiceWard ward = serviceability.servedWard(tenant, request.wardNo());
        List<ServiceabilityView.Fee> fees = serviceability.effectiveFees(tenant, ward).stream()
            .map(ServiceabilityView.Fee::of)
            .toList();
        Map<String, Boolean> flags = featureFlags.effective(tenant.getId());
        return new ServiceabilityView(CitySummary.of(tenant), WardSummary.of(ward), fees, flags);
    }

    /**
     * {@code ward_no} is required. The pair {@code (city_code, ward_no)} is what a ward <em>is</em>
     * in this API (§4.2); a body that names only a city is not a half-answered question but a
     * different one — {@code GET /cities} — and answering it here would let a client believe a whole
     * city is servable.
     */
    public record CheckRequest(
        @NotBlank @Size(max = 32) String cityCode,
        @NotNull @Positive Integer wardNo,
        @Size(max = 6) String pincode,
        @Size(max = 500) String address) {
    }
}
