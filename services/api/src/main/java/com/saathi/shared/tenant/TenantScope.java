package com.saathi.shared.tenant;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.Set;

/**
 * The tenant scope carried by an authenticated principal (§3, §11.1).
 *
 * <p>Two rules this object exists to enforce: the scope is resolved from the session and is
 * never read from a request parameter (PRD §6), and a cross-tenant read answers
 * {@code 404 NOT_FOUND} rather than {@code 403} so the platform never confirms that another
 * city's row exists (PRD §13.6-13.7, §4.3-5).
 */
public record TenantScope(String tenantId, String actorId, PrincipalKind actorType, Set<String> permissions) {

    public TenantScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for a scoped principal");
        }
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public void require(String permission) {
        if (!permissions.contains(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "The required permission is not granted.");
        }
    }

    /** Deny-by-default check for an endpoint that may only be reached by a platform admin. */
    public void requireSuperAdmin() {
        if (actorType != PrincipalKind.SUPER_ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Platform-wide configuration is super-admin only.");
        }
    }

    /** Answer 404, not 403, for anything outside the caller's tenant. */
    public void requireSameTenant(String resourceTenantId) {
        if (!tenantId.equals(resourceTenantId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found in the caller's scope.");
        }
    }

    /** Permission resolved but the scope was wrong: never names the resource (§10.2). */
    public void requireScopeGranted(boolean granted) {
        if (!granted) {
            throw new ApiException(ErrorCode.TENANT_SCOPE_VIOLATION, "The requested scope is not granted.");
        }
    }

    public boolean isSystem() {
        return actorType == PrincipalKind.SYSTEM;
    }
}
