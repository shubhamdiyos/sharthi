package com.saathi.identity.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.identity.session.SessionService;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.ServiceabilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own surface (§10.5.1): what it may do, and which city the storefront is standing in.
 *
 * <p>{@code GET /me} is the whole of §10.4's argument that a portal must not decide its own buttons:
 * the scopes, the permissions and the kill switches are read from the rows on every call, so a
 * revoked grant changes what a screen offers without a redeploy and without the client noticing that
 * anything changed underneath it.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeController {

    private final PrincipalViewFactory principalViews;
    private final SessionService sessions;
    private final ServiceabilityService serviceability;

    @GetMapping("/me")
    public PrincipalView me(AuthenticatedPrincipal caller) {
        return principalViews.of(caller);
    }

    /**
     * Stores the ward the storefront resolves its city from (§10.1). It is addressed by
     * {@code (city_code, ward_no)} and not by a ward id, because §4.3-6 makes the public pair the
     * only way a client names geography — and because a body that carried an internal id would be a
     * body in which a caller could try to name a ward it was not shown.
     *
     * <p>The answer is a fresh {@code PrincipalView}, not a 204: this call changes the caller's scope
     * list, and §10.4 makes that list the client's only source of truth, so the response that leaves
     * it stale is the response that makes the next screen wrong.
     *
     * <p>Staff are refused. An operator's, rider's or merchant member's tenant comes from a grant
     * bound at login (§12.4), and letting a request body move it would put a second source of scope
     * next to the one §11.1 exists to be the only one.
     */
    @PutMapping("/me/ward")
    public PrincipalView bindWard(@Valid @RequestBody WardRequest body, AuthenticatedPrincipal caller) {
        if (caller.kind() != PrincipalKind.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A city is granted to staff, not chosen by them.");
        }
        Tenant tenant = serviceability.liveTenant(body.cityCode());
        ServiceWard ward = serviceability.servedWard(tenant, body.wardNo());
        sessions.bindWard(caller, tenant.getId(), ward.getId());
        return principalViews.of(caller.withWard(tenant.getId(), ward.getId()));
    }

    /** One ward, named the way the public API names everything. */
    public record WardRequest(
        @NotBlank @Size(max = 32) String cityCode,
        @NotNull @Positive Integer wardNo) {
    }
}
