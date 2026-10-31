package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.order.domain.Order;
import com.saathi.order.repository.OrderRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.tenant.PrincipalKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The two scope rules every endpoint in §10.5.6 repeats, written once.
 *
 * <p>A shop's order is found by {@code (session tenant, session merchant, code)} and nothing else.
 * There is no {@code merchant} parameter in any path or body because §10.7 forbids one: a member of
 * one shop cannot name another by changing a string, and the answer to a code that belongs to a
 * different shop is {@code NOT_FOUND}, not {@code FORBIDDEN} — §10.2 routes a cross-merchant read to
 * the same refusal as a nonexistent order so the endpoint cannot be used to enumerate who else is
 * trading in the city.
 *
 * <p>The permission check happens before the lookup, never after. Checking the key on the row would
 * mean loading a neighbouring shop's order, deciding it is not yours, and only then refusing — which
 * answers the question the refusal exists to keep open.
 */
@Component
@RequiredArgsConstructor
public class OrderScope {

    private final OrderRepository orders;

    /** The shop this session acts for, or the refusal a customer or a platform grant earns here. */
    public String shopIdOf(AuthenticatedPrincipal caller) {
        if (caller.kind() != PrincipalKind.MERCHANT_STAFF) {
            throw new ApiException(ErrorCode.FORBIDDEN, "These orders belong to a shop.");
        }
        if (caller.merchantId() == null || caller.merchantId().isBlank()) {
            throw new ApiException(ErrorCode.TENANT_SCOPE_VIOLATION,
                "This session is not bound to a shop.");
        }
        return caller.merchantId();
    }

    /**
     * The shop a list or a queue read is scoped to, after {@code permission}.
     *
     * <p>The permission is checked first because it is the cheaper and the more informative refusal:
     * a customer session that reaches a merchant endpoint holds no merchant permission, and
     * {@code FORBIDDEN} is true about the caller. Asking whether the caller is bound to a shop before
     * that would answer a scope question to someone who never had the right to ask it.
     */
    public String shopOf(AuthenticatedPrincipal caller, String permission) {
        caller.require(permission);
        return shopIdOf(caller);
    }

    /** The order this shop may act on through {@code permission}, or {@code NOT_FOUND}. */
    public Order shopOrder(AuthenticatedPrincipal caller, String permission, String code) {
        caller.require(permission);
        String tenantId = caller.requireScope().tenantId();
        return orders.findByTenantIdAndMerchantIdAndCode(tenantId, shopIdOf(caller), code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that order."));
    }

    /**
     * The order this customer owns, through {@code permission}.
     *
     * <p>The refusal for an order that belongs to somebody else is {@code NOT_FOUND} rather than the
     * {@code 403} §10.6.2's error column sketches, for the reason §10.2 gives for every other
     * customer-scoped read: an order code that answers 403 is a code that exists, and the code is
     * printed on the customer's receipt.
     */
    public Order customerOrder(AuthenticatedPrincipal caller, String permission, String code) {
        caller.require(permission);
        if (caller.kind() != PrincipalKind.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Orders here belong to a customer.");
        }
        if (caller.tenantId() == null || caller.tenantId().isBlank()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "Choose your area before you answer a total.");
        }
        return orders.findByTenantIdAndCustomerIdAndCode(caller.tenantId(), caller.accountId(), code)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that order."));
    }
}
