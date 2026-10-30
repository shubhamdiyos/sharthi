package com.saathi.identity.session;

import com.saathi.shared.tenant.PrincipalKind;
import java.time.Duration;

/**
 * §12.4's session table, as data rather than as prose somebody has to remember.
 *
 * <p>The two columns are different controls and only look similar: an <em>idle</em> timeout is a
 * sliding window that a shared device resets every time it is used, and an <em>absolute</em> one is
 * a wall a session cannot get past however often it is used. A rider's device at a shop is the case
 * that needs both — 12 hours absolute, because the next person to pick the phone up is a different
 * human.
 *
 * <p>A customer's absolute timeout has no value that means "none". {@link #NO_ABSOLUTE} is ten
 * years, which is what {@code user_session.absolute_expires_at NOT NULL} leaves a reader of §12.4
 * with: the column exists, so a number has to go in it, and the number has to be far enough out
 * that revocation is the only way the session ends. Flagged as a V2 follow-up — the honest schema
 * for this row is a nullable column.
 */
public record SessionPolicy(Duration idleTimeout, Duration absoluteTimeout) {

    private static final Duration NO_ABSOLUTE = Duration.ofDays(3650);

    public static SessionPolicy forKind(PrincipalKind kind) {
        return switch (kind) {
            case CUSTOMER -> new SessionPolicy(Duration.ofDays(30), NO_ABSOLUTE);
            case RIDER -> new SessionPolicy(Duration.ofHours(12), Duration.ofHours(12));
            case MERCHANT_STAFF -> new SessionPolicy(Duration.ofHours(1), Duration.ofHours(12));
            case OPERATOR -> new SessionPolicy(Duration.ofMinutes(45), Duration.ofHours(12));
            case SUPER_ADMIN -> new SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(4));
            // A job is not a device. §12.4 has no row for SYSTEM because §5.1's
            // session_principal_kind CHECK has none either: a batch run must not be able to
            // appear in GET /auth/sessions as if a person were at a keyboard.
            case SYSTEM -> throw new IllegalArgumentException("a SYSTEM actor has no session");
        };
    }
}
