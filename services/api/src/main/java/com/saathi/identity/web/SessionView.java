package com.saathi.identity.web;

import com.saathi.identity.domain.UserSession;
import java.time.Instant;

/**
 * One of the caller's own devices (§12.5).
 *
 * <p>{@code revoked} never appears because a revoked row is not in the list: §10.5.1's
 * {@code GET /auth/sessions} shows the devices that exist now, and the repository filter is
 * {@code revoked_at is null}. {@code expired} is the other half of the distinction §12.5 asks for —
 * a session the server still has but that no longer opens a door is a device the customer should be
 * able to see disappearing, not a row quietly dropped from the list.
 *
 * <p>{@code ip_hash} is the only address-shaped thing here, and it is a keyed hash (§13.4): enough
 * for an owner to notice that a login came from somewhere they do not recognise, useless to anyone
 * trying to turn the response into a location.
 */
public record SessionView(String id, String deviceLabel, String ipHash, Instant lastSeenAt,
                          Instant idleExpiresAt, Instant absoluteExpiresAt, boolean current, boolean expired) {

    public static SessionView of(UserSession session, String currentSessionId, Instant now) {
        return new SessionView(
            session.getId(),
            session.getDeviceLabel(),
            session.getIpHash(),
            session.getLastSeenAt(),
            session.getIdleExpiresAt(),
            session.getAbsoluteExpiresAt(),
            session.getId().equals(currentSessionId),
            !session.isUsableAt(now));
    }
}
