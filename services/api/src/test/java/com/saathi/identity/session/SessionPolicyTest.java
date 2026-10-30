package com.saathi.identity.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.tenant.PrincipalKind;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * §12.4's timeout table, checked as data.
 *
 * <p>Every session row in the system is issued from these five numbers, so a change here is a policy
 * change and not a refactor — and the wire tests can only observe the kinds that can sign in.
 */
class SessionPolicyTest {

    @Test
    void eachKindGetsThePairItsRowInSectionTwelvePointFourStates() {
        // The customer's absolute timeout is ten years rather than "none", because
        // user_session.absolute_expires_at is NOT NULL. Flagged on SessionPolicy as a schema question.
        assertEquals(new SessionPolicy(Duration.ofDays(30), Duration.ofDays(3650)),
            SessionPolicy.forKind(PrincipalKind.CUSTOMER));
        assertEquals(new SessionPolicy(Duration.ofHours(12), Duration.ofHours(12)),
            SessionPolicy.forKind(PrincipalKind.RIDER));
        assertEquals(new SessionPolicy(Duration.ofHours(1), Duration.ofHours(12)),
            SessionPolicy.forKind(PrincipalKind.MERCHANT_STAFF));
        assertEquals(new SessionPolicy(Duration.ofMinutes(45), Duration.ofHours(12)),
            SessionPolicy.forKind(PrincipalKind.OPERATOR));
        assertEquals(new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(4)),
            SessionPolicy.forKind(PrincipalKind.SUPER_ADMIN));
    }

    @Test
    void noKindIsAllowedToExpireIdleBeforeItExpiresAbsolutely() {
        // user_session's session_expiry_ordered CHECK refuses a row where idle_expires_at is past
        // absolute_expires_at. A policy with that shape would not fail here; it would fail at the
        // first sign-in of that kind, in production, with a constraint message.
        Arrays.stream(PrincipalKind.values())
            .filter(kind -> kind != PrincipalKind.SYSTEM)
            .forEach(kind -> assertTrue(SessionPolicy.forKind(kind).idleTimeout()
                    .compareTo(SessionPolicy.forKind(kind).absoluteTimeout()) <= 0,
                kind + " would be unissuable"));
    }

    @Test
    void aJobIsNotADevice() {
        // §12.4 has no row for SYSTEM, and §5.1's session_principal_kind CHECK has none either. The
        // refusal has to be here rather than a null policy, or a batch run becomes a fake human in
        // somebody's GET /auth/sessions.
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> SessionPolicy.forKind(PrincipalKind.SYSTEM));
        assertEquals("a SYSTEM actor has no session", refusal.getMessage());
    }
}
