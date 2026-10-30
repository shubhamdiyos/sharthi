package com.saathi.identity.session;

import com.saathi.identity.domain.Role;
import com.saathi.identity.domain.UserAccount;
import com.saathi.identity.domain.UserRoleGrant;
import com.saathi.identity.domain.AccountState;
import com.saathi.identity.domain.UserSession;
import com.saathi.identity.repository.RoleRepository;
import com.saathi.identity.repository.UserAccountRepository;
import com.saathi.identity.repository.UserRoleGrantRepository;
import com.saathi.identity.repository.UserSessionRepository;
import com.saathi.identity.security.IdentityKeys;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.PrincipalKind;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, reads and revokes the rows that decide access (§12.4).
 *
 * <p>{@link #resolve} is called on every authenticated request and does the whole check from the
 * database: cookie MAC, then the row's own {@code revoked_at} / {@code idle_expires_at} /
 * {@code absolute_expires_at}, then the account's state, then the live grants. That is five queries
 * where a cached token would need none, and it is the point — §12.5 promises that a revoked grant
 * or a force logout ends access at the next request, and a cache is a promise that ends access
 * whenever the cache notices. For a pilot of the size §1 describes, correctness is the cheaper
 * option; the profile for a bigger one is §4.3's filter, not a shorter path here.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final UserSessionRepository sessions;
    private final UserAccountRepository accounts;
    private final UserRoleGrantRepository grants;
    private final RoleRepository roles;
    private final IdentityKeys keys;
    private final Clock clock;

    /**
     * Issues a session and hands back the cookie value. The scopes are bound here from the
     * principal's live grants and never again afterwards: a session that re-read the grant set on
     * each use would let a second city's operator drift into the first one's rows by changing a
     * parameter (§10.1).
     */
    @Transactional
    public IssuedSession issue(UserAccount account, String deviceLabel, String ipHash, String userAgentHash) {
        Instant now = clock.instant();
        var policy = SessionPolicy.forKind(account.getKind());
        List<UserRoleGrant> live = grants.findByUserAccountIdAndRevokedAtIsNull(account.getId());

        UserSession session = UserSession.builder()
            .id(Ulid.next(clock))
            .userAccountId(account.getId())
            .principalKind(account.getKind())
            .tenantScope(singleTenantOf(live))
            .wardScope(null)
            .merchantScope(singleMerchantOf(live))
            .createdAt(now)
            .lastSeenAt(now)
            .idleExpiresAt(now.plus(policy.idleTimeout()))
            .absoluteExpiresAt(now.plus(policy.absoluteTimeout()))
            .deviceLabel(deviceLabel)
            .ipHash(ipHash)
            .userAgentHash(userAgentHash)
            .build();
        sessions.save(session);

        String credential = credential(session.getId());
        return new IssuedSession(credential, session.getId(), session.getAbsoluteExpiresAt(),
            keys.csrfToken(session.getId()));
    }

    /**
     * Issues a session for a principal that has just authenticated and reads the credential back
     * through {@link #resolve} — the same path every later request takes. The extra queries at
     * sign-in are the cheapest proof that the row, its scopes and its MAC agree, and the response has
     * to describe a principal anyway.
     */
    @Transactional
    public SignedIn issueForAccount(UserAccount account, RequestTags.Device device) {
        IssuedSession issued = issue(account, device.deviceLabel(), device.ipHash(), device.userAgentHash());
        AuthenticatedPrincipal principal = resolve(issued.cookieValue())
            .orElseThrow(() -> new IllegalStateException(
                "a session issued by this request is not usable: " + issued.sessionId()));
        return new SignedIn(principal, issued);
    }

    /** The request-scoped caller, or empty for a cookie that no longer means anything. */
    @Transactional
    public Optional<AuthenticatedPrincipal> resolve(String cookieValue) {
        Optional<SessionCredential> parsed = SessionCredential.parse(cookieValue);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        SessionCredential credential = parsed.get();
        if (!keys.sessionMacMatches(credential.sessionId(), credential.mac())) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        UserSession session = sessions.findById(credential.sessionId()).orElse(null);
        if (session == null || !session.isUsableAt(now)) {
            return Optional.empty();
        }
        UserAccount account = accounts.findById(session.getUserAccountId()).orElse(null);
        if (account == null || account.getState() == AccountState.DEACTIVATED) {
            return Optional.empty();
        }

        session.setLastSeenAt(now);
        var policy = SessionPolicy.forKind(session.getPrincipalKind());
        // Slid forward from *now*, which is what "30 days sliding" means; clamped by the absolute
        // expiry so a session in use since before §12.4's wall cannot push its own deadline out.
        Instant slid = now.plus(policy.idleTimeout());
        session.setIdleExpiresAt(slid.isBefore(session.getAbsoluteExpiresAt()) ? slid : session.getAbsoluteExpiresAt());
        sessions.save(session);

        List<UserRoleGrant> live = grants.findByUserAccountIdAndRevokedAtIsNull(account.getId());
        Set<String> permissions = live.isEmpty()
            ? Set.of()
            : Set.copyOf(roles.findPermissionKeys(live.stream().map(UserRoleGrant::getRoleId).distinct().toList()));

        return Optional.of(new AuthenticatedPrincipal(
            session.getId(),
            account.getId(),
            session.getPrincipalKind(),
            account.getDisplayName(),
            account.getPhone(),
            account.getEmail(),
            session.getTenantScope(),
            session.getWardScope(),
            session.getMerchantScope(),
            permissions));
    }

    /**
     * Revokes one session: the caller's own, or another principal's with
     * {@code USER_FORCE_LOGOUT} inside the caller's bound tenant (§10.5.1).
     *
     * <p>Everything that does not match returns false rather than a refusal that explains itself. A
     * session belonging to somebody else, in another city, or already gone is one answer —
     * {@code NOT_FOUND} — because confirming that another principal has a live device is exactly the
     * existence §4.3-5 refuses to leak.
     *
     * <p>The force-logout half fails closed today: V3 seeds no {@code permission} rows, so no
     * principal holds {@code USER_FORCE_LOGOUT} and no session but one's own is revocable. The
     * branch is here so that the moment §11.2's operator permissions land in a migration, the
     * endpoint already enforces them instead of acquiring the capability by accident. Platform-wide
     * revocation by a tenantless super-admin stays out: §4.4 makes cross-tenant access a granted,
     * audited act, and that grant does not exist yet.
     */
    @Transactional
    public boolean revoke(AuthenticatedPrincipal caller, String sessionId, String reason) {
        UserSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getRevokedAt() != null) {
            return false;
        }
        boolean own = session.getUserAccountId().equals(caller.accountId());
        if (!own) {
            boolean mayForce = caller.permissions().contains("USER_FORCE_LOGOUT");
            boolean sameTenant = caller.tenantId() != null && caller.tenantId().equals(session.getTenantScope());
            if (!mayForce || !sameTenant) {
                return false;
            }
        }
        session.setRevokedAt(clock.instant());
        session.setRevokeReason(reason);
        sessions.save(session);
        return true;
    }

    /** The session's current revocation state, for {@code GET /auth/sessions}. */
    @Transactional(readOnly = true)
    public List<UserSession> liveSessionsOf(String accountId) {
        return sessions.findByUserAccountIdAndRevokedAtIsNullOrderByLastSeenAtDesc(accountId);
    }

    /**
     * Points the session at a ward, which is how a customer's city gets decided (§10.1). The
     * session is named by the resolved caller, not by a request parameter, and the row's owner is
     * re-checked before it moves: an id in a URL is not evidence that the session belongs to
     * whoever typed it.
     */
    @Transactional
    public void bindWard(AuthenticatedPrincipal principal, String tenantId, String wardId) {
        sessions.findById(principal.sessionId()).ifPresent(session -> {
            if (!session.getUserAccountId().equals(principal.accountId())) {
                return;
            }
            session.setTenantScope(tenantId);
            session.setWardScope(wardId);
            sessions.save(session);
            if (principal.kind() == PrincipalKind.CUSTOMER) {
                grantCustomerRole(principal, tenantId);
            }
        });
    }

    /**
     * A customer's permissions come from the same {@code user_role_grant} rows as everyone else's
     * (§11.3), and a CUSTOMER grant needs a tenant — V2's trigger accepts a tenantless grant only
     * for a platform role. Registration happens before a city is chosen, so the grant is written at
     * the first ward binding, which is the moment the customer's city becomes a fact (§10.1). The
     * second city a customer shops in gets its own grant, exactly as the seed's two shoppers do.
     */
    private void grantCustomerRole(AuthenticatedPrincipal principal, String tenantId) {
        Role customerRole = roles.findByKey("CUSTOMER").orElseThrow(
            () -> new IllegalStateException("the CUSTOMER role is missing from the reference data"));
        boolean held = grants.findByUserAccountIdAndTenantIdAndRevokedAtIsNull(
                principal.accountId(), tenantId).stream()
            .anyMatch(grant -> grant.getRoleId().equals(customerRole.getId()));
        if (held) {
            return;
        }
        grants.save(UserRoleGrant.builder()
            .id(Ulid.next(clock))
            .userAccountId(principal.accountId())
            .roleId(customerRole.getId())
            .tenantId(tenantId)
            .grantedBy(principal.accountId())
            .grantedAt(clock.instant())
            .reason("Registered on the storefront")
            .build());
    }

    private String credential(String sessionId) {
        return new SessionCredential(sessionId, keys.sessionMac(sessionId)).asCookieValue();
    }

    /**
     * One tenant, or none. A principal with live grants in two cities is not guessed between: the
     * session carries no tenant and the portal's city picker becomes the thing that creates a
     * session per city, which is the only version of this where "which tenant did this write belong
     * to" has one answer (§11.3).
     */
    private static String singleTenantOf(List<UserRoleGrant> live) {
        Set<String> tenants = live.stream()
            .map(UserRoleGrant::getTenantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return tenants.size() == 1 ? tenants.iterator().next() : null;
    }

    /**
     * The shop a merchant member's session is bound to (§12.4), by the same one-or-none rule as
     * the tenant. {@code merchantId} is what every {@code /m/*} endpoint checks its rows against,
     * so a member granted two shops carries none and the portal must ask which shop it is acting
     * for — the alternative is a session that silently picks the first shop in the list.
     */
    private static String singleMerchantOf(List<UserRoleGrant> live) {
        Set<String> merchantIds = live.stream()
            .map(UserRoleGrant::getMerchantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return merchantIds.size() == 1 ? merchantIds.iterator().next() : null;
    }

    /**
     * @param cookieValue  the value to set in {@code saathi_session}
     * @param sessionId    the row's id, which is also what the CSRF token is keyed on
     * @param expiresAt    the cookie's own expiry: the browser is told the absolute deadline, the
     *                     server still checks the row
     * @param csrfToken    the value to mirror into the readable {@code saathi_csrf} cookie
     */
    public record IssuedSession(String cookieValue, String sessionId, Instant expiresAt, String csrfToken) {
    }
}
