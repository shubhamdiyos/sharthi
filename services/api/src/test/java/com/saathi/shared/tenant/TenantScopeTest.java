package com.saathi.shared.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two rules this object exists for: a scope is never inferred from a parameter, and a
 * cross-tenant read answers 404 — confirming that another city's row exists is itself a leak
 * (PRD §13.6-13.7, §4.3-5).
 */
class TenantScopeTest {

    private static final String SIWAN = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String OTHER = "01ARZ3NDEKTSV4RRFFQ69G5FB0";

    private static TenantScope scope(PrincipalKind kind, Set<String> permissions) {
        return new TenantScope(SIWAN, "01ARZ3NDEKTSV4RRFFQ69G5FAW", kind, permissions);
    }

    @Test
    void aScopeWithoutATenantIsNotConstructible() {
        // No "global" TenantScope: platform-wide reads are a distinct, audited path, never an
        // accidentally-unset field on the ordinary one.
        assertThrows(IllegalArgumentException.class,
            () -> new TenantScope(null, "a", PrincipalKind.OPERATOR, Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new TenantScope("  ", "a", PrincipalKind.OPERATOR, Set.of()));
    }

    @Test
    void aMissingPermissionIsAFourOhThreeNotAFourOhFour() {
        // FORBIDDEN is defined as "authenticated, permission absent on a resource the caller
        // can see" (§10.2). The resource's existence is already known, so there is nothing to hide.
        TenantScope s = scope(PrincipalKind.MERCHANT_STAFF, Set.of("ORDER_READ"));
        ApiException e = assertThrows(ApiException.class, () -> s.require("ORDER_WRITE"));
        assertEquals(ErrorCode.FORBIDDEN, e.code());
        assertEquals(403, e.code().statusCode());
    }

    @Test
    void anotherTenantsRowIsNotFoundRatherThanForbidden() {
        TenantScope s = scope(PrincipalKind.OPERATOR, Set.of("ORDER_READ"));
        ApiException e = assertThrows(ApiException.class, () -> s.requireSameTenant(OTHER));
        assertEquals(ErrorCode.NOT_FOUND, e.code(), "the response must not confirm the row exists");
        assertEquals(404, e.code().statusCode());
        assertThrows(ApiException.class, () -> s.requireSameTenant(null));
    }

    @Test
    void onesOwnTenantPassesTheSameCheck() {
        TenantScope s = scope(PrincipalKind.OPERATOR, Set.of("ORDER_READ"));
        s.requireSameTenant(SIWAN);
    }

    @Test
    void platformConfigurationIsSuperAdminOnly() {
        assertThrows(ApiException.class,
            () -> scope(PrincipalKind.OPERATOR, Set.of("RATE_WRITE")).requireSuperAdmin());
        assertThrows(ApiException.class,
            () -> scope(PrincipalKind.SYSTEM, Set.of()).requireSuperAdmin());
        scope(PrincipalKind.SUPER_ADMIN, Set.of()).requireSuperAdmin();
    }

    @Test
    void aGrantedPermissionOutsideTheScopeIsAViolationThatNamesNothing() {
        TenantScope s = scope(PrincipalKind.SUPER_ADMIN, Set.of("WARD_READ"));
        ApiException e = assertThrows(ApiException.class, () -> s.requireScopeGranted(false));
        assertEquals(ErrorCode.TENANT_SCOPE_VIOLATION, e.code());
        assertEquals(403, e.code().statusCode());
        assertTrue(!e.getMessage().contains(SIWAN) && !e.getMessage().contains(OTHER),
            "§10.2: never names the resource");
        s.requireScopeGranted(true);
    }

    @Test
    void permissionsCannotBeRewrittenAfterTheScopeIsIssued() {
        Set<String> mutable = new HashSet<>();
        mutable.add("ORDER_READ");
        TenantScope s = scope(PrincipalKind.OPERATOR, mutable);
        mutable.add("SETTLEMENT_APPROVE");
        assertThrows(ApiException.class, () -> s.require("SETTLEMENT_APPROVE"));
        s.require("ORDER_READ");
        // A null permission set means "denies everything", never "grants everything".
        assertThrows(ApiException.class, () -> scope(PrincipalKind.OPERATOR, null).require("ORDER_READ"));
    }

    @Test
    void anEmptyPermissionListDeniesEverything() {
        TenantScope none = scope(PrincipalKind.RIDER, Set.of());
        assertThrows(ApiException.class, () -> none.require("anything"));
        assertTrue(none.permissions().isEmpty());
    }

    @Test
    void onlyTheSystemPrincipalIsReportedAsUnattended() {
        assertTrue(scope(PrincipalKind.SYSTEM, Set.of()).isSystem());
        assertTrue(!scope(PrincipalKind.SUPER_ADMIN, Set.of()).isSystem());
    }

    @Test
    void everyPrincipalKindIsUsableInAScope() {
        // The audit table's actor_type CHECK enumerates the same six values in the same order
        // of meaning; a seventh kind must arrive with a migration, not quietly.
        assertEquals(6, PrincipalKind.values().length);
        for (PrincipalKind kind : PrincipalKind.values()) {
            assertEquals(kind, scope(kind, Set.of()).actorType());
        }
    }
}
