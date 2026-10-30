package com.saathi.identity.session;

import com.saathi.shared.contact.EmailNormaliser;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.shared.tenant.TenantScope;
import java.util.Set;

/**
 * The resolved caller, as a snapshot.
 *
 * <p>No JPA entity is in here on purpose. {@code open-in-view} is off (§2.2), so anything the
 * request filter puts on the request outlives its transaction; a detached entity would be a
 * {@code LazyInitializationException} on the first controller that touched a relation. A record of
 * plain values is also the smallest thing that can answer "who is asking", which is the only thing
 * a controller is allowed to know before it has checked a permission.
 *
 * @param tenantId the scope bound at login, never a request parameter (§10.1); null for a
 *                 customer who has not chosen a city yet and for a platform grant
 * @param wardId   the customer's selected ward, which is how the storefront resolves a tenant
 *                 (§10.1) — the field the cart path will read instead of a query string
 */
public record AuthenticatedPrincipal(
    String sessionId,
    String accountId,
    PrincipalKind kind,
    String displayName,
    String phone,
    String email,
    String tenantId,
    String wardId,
    String merchantId,
    Set<String> permissions) {

    public AuthenticatedPrincipal {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * The scope an endpoint needs to do tenant-scoped work (§11.1).
     *
     * <p>A platform admin has no tenant bound at login and that is not a licence to act across all
     * of them: §4.4's rule is that every mutation is bound to exactly one tenant from the
     * principal's grant set, so a tenantless session reaches a city by holding a per-tenant grant,
     * not by asking. {@code TENANT_SCOPE_VIOLATION} rather than {@code FORBIDDEN} because the
     * permission may well be held — it is the scope that is missing.
     */
    public TenantScope requireScope() {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApiException(ErrorCode.TENANT_SCOPE_VIOLATION,
                "This session is not bound to a tenant.");
        }
        return new TenantScope(tenantId, accountId, kind, permissions);
    }

    public void require(String permission) {
        requireScope().require(permission);
    }

    /** §10.4's {@code masked_contact}: enough for a person to recognise themselves, no more. */
    public String maskedContact() {
        return email != null ? EmailNormaliser.mask(email) : PhoneNormaliser.mask(phone);
    }

    /**
     * The same caller with a different city, for the response to {@code PUT /me/ward}.
     *
     * <p>This is a copy and not a mutation of the request attribute because the snapshot the filter
     * put on the request has to stay what it was when the request arrived: the CSRF interceptor and
     * anything else that reads it later in this same dispatch must not see a scope that moved
     * mid-request (§10.1). The row is written first, so the copy describes the state the next request
     * will resolve.
     */
    public AuthenticatedPrincipal withWard(String newTenantId, String newWardId) {
        return new AuthenticatedPrincipal(sessionId, accountId, kind, displayName, phone, email,
            newTenantId, newWardId, merchantId, permissions);
    }
}
