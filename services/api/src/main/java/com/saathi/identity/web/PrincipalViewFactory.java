package com.saathi.identity.web;

import com.saathi.identity.domain.UserRoleGrant;
import com.saathi.identity.repository.UserRoleGrantRepository;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.repository.TenantRepository;
import com.saathi.tenancy.service.FeatureFlagReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles §10.4's {@code PrincipalView} from rows, never from the request.
 *
 * <p>The scopes come from the live grant set re-read here rather than from the three scope columns
 * the session was created with. That is a deliberate second read: {@code SessionService} keeps the
 * session's bound scope frozen so a mutation cannot drift between cities mid-request (§10.1), while
 * {@code GET /me} must answer "what could you act on <em>now</em>" — a grant revoked a minute ago has
 * to be gone from this list even though the session that used to carry it is still live. A client
 * that is shown a scope it can no longer use will produce a {@code TENANT_SCOPE_VIOLATION} it cannot
 * explain, which is §10.4's promise broken in the one place it is load-bearing.
 *
 * <p>The session's own binding narrows the grant set for the city the caller is acting in. That is
 * the customer case: the CUSTOMER grant names only the tenant, while the ward is the one the session
 * bound (§10.1). Letting the grant's tenant-only scope stand would drop the ward from the very
 * response that just answered the binding, so for the session's tenant the session's narrower scope
 * replaces the grant's.
 */
@Component
@RequiredArgsConstructor
public class PrincipalViewFactory {

    private final UserRoleGrantRepository grants;
    private final TenantRepository tenants;
    private final FeatureFlagReader featureFlags;

    @Transactional(readOnly = true)
    public PrincipalView of(AuthenticatedPrincipal caller) {
        List<UserRoleGrant> live = grants.findByUserAccountIdAndRevokedAtIsNull(caller.accountId());
        Map<String, String> codes = codesOf(live, caller.tenantId());

        boolean sessionNarrower = caller.tenantId() != null
            && (caller.wardId() != null || caller.merchantId() != null);

        // A LinkedHashSet rather than a list because two grants of one role in one city — operator
        // and support, say — describe one place the caller can act, and a client that iterates
        // scopes to build a city picker would render the same city twice.
        Set<PrincipalView.Scope> scopes = new LinkedHashSet<>();
        for (UserRoleGrant grant : live) {
            if (sessionNarrower && caller.tenantId().equals(grant.getTenantId())) {
                continue;
            }
            scopes.add(scopeOf(grant.getTenantId(), codes, grant.getMerchantId(), grant.getWardId()));
        }
        boolean sessionTenantGranted = scopes.stream()
            .anyMatch(s -> Objects.equals(s.tenantId(), caller.tenantId()));
        if (caller.tenantId() != null && (sessionNarrower || !sessionTenantGranted)) {
            scopes.add(scopeOf(caller.tenantId(), codes, caller.merchantId(), caller.wardId()));
        }

        return PrincipalView.of(caller, List.copyOf(scopes), featureFlags.effective(caller.tenantId()));
    }

    /**
     * A platform grant has no tenant, no merchant and no ward, so it serialises as an empty object
     * under §10.1's "fields absent preferred over fields nulled". That is the honest shape: a
     * super-admin's scope is the platform, which is not a place a client can navigate to.
     */
    private static PrincipalView.Scope scopeOf(String tenantId, Map<String, String> codes,
                                               String merchantId, String wardId) {
        return new PrincipalView.Scope(tenantId, tenantId == null ? null : codes.get(tenantId), merchantId, wardId);
    }

    private Map<String, String> codesOf(List<UserRoleGrant> live, String sessionTenantId) {
        List<String> ids = new ArrayList<>();
        for (UserRoleGrant grant : live) {
            if (grant.getTenantId() != null) {
                ids.add(grant.getTenantId());
            }
        }
        if (sessionTenantId != null && !ids.contains(sessionTenantId)) {
            ids.add(sessionTenantId);
        }
        Map<String, String> codes = new TreeMap<>();
        for (Tenant tenant : tenants.findAllById(ids)) {
            codes.put(tenant.getId(), tenant.getCode());
        }
        return codes;
    }
}
