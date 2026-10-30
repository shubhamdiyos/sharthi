package com.saathi.identity.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §10.4's {@code PrincipalView} — the only place a client learns what it is allowed to do.
 *
 * <p>§10.5.1's note on {@code GET /me} is the design: "the client's only source of permissions". A
 * portal that decides which buttons to show from its own idea of what a customer can do is a portal
 * that keeps offering actions §11.3 has just refused, so the server answers with the resolved set and
 * the client obeys it.
 *
 * <p>With {@code default-property-inclusion: non_null} the null members vanish rather than appear as
 * nulls, which is §10.1's stated preference: a scope with no merchant simply has no
 * {@code merchant_id} key.
 *
 * @param permissions server-resolved permission keys; empty today because V3 seeds no permission rows
 *                    and no module has claimed one
 */
public record PrincipalView(Who principal, List<Scope> scopes, Set<String> permissions, StepUp stepUp,
                            Map<String, Boolean> flags) {

    public record Who(String id, String kind, String displayName, String maskedContact) {
    }

    /** One (tenant, merchant, ward) triple this caller can act in — never a client input (§10.1). */
    public record Scope(String tenantId, String tenantCode, String merchantId, String wardId) {
    }

    /**
     * §12.6's step-up state. {@code requiredFor} is empty for every caller today: the privileged
     * writes that would populate it arrive with the modules that enforce them, and a list of keys
     * nothing checks yet would be {@code GET /me} promising a rule that does not exist.
     */
    public record StepUp(List<String> requiredFor, Instant grantedUntil) {

        public static StepUp none() {
            return new StepUp(List.of(), null);
        }
    }

    public static PrincipalView of(AuthenticatedPrincipal caller, List<Scope> scopes, Map<String, Boolean> flags) {
        return new PrincipalView(
            new Who(caller.accountId(), caller.kind().name(), caller.displayName(), caller.maskedContact()),
            scopes, caller.permissions(), StepUp.none(), flags);
    }
}
