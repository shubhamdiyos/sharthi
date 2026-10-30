package com.saathi.identity.session;

import com.saathi.shared.id.Ulid;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The value of the session cookie: this row's id, a dot, and an HMAC of the id.
 *
 * <p>Signing the id is not what authorises anything — §12.4's rule is that the row decides, and the
 * filter loads it on every request. It is there so that a random 26-character string cannot be
 * turned into a session by guessing: without the MAC, {@code user_session}'s primary key would be
 * the only thing between a made-up cookie and a database lookup, and a ULID is not secret.
 *
 * <p>Both halves are opaque to the client and neither is a person's identifier. The id is a ULID
 * with a creation timestamp in it, which is the reason the MAC covers the whole string: it stops
 * somebody who can read one session id from constructing a neighbour's.
 */
public record SessionCredential(String sessionId, String mac) {

    public static final String COOKIE_NAME = "saathi_session";
    /** The double-submit half of §12.4's CSRF rule — readable by the page, sent as a header. */
    public static final String CSRF_COOKIE_NAME = "saathi_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final int MAC_HEX_LENGTH = 64;
    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final int LENGTH = Ulid.LENGTH + 1 + MAC_HEX_LENGTH;

    public String asCookieValue() {
        return sessionId + "." + mac;
    }

    /** Empty for anything that is not exactly {@code <26 Crockford>.<64 lowercase hex>}. */
    public static Optional<SessionCredential> parse(String raw) {
        if (raw == null || raw.length() != LENGTH) {
            return Optional.empty();
        }
        String id = raw.substring(0, Ulid.LENGTH);
        if (raw.charAt(Ulid.LENGTH) != '.' || !Ulid.isValid(id)) {
            return Optional.empty();
        }
        String mac = raw.substring(Ulid.LENGTH + 1);
        return HEX.matcher(mac).matches()
            ? Optional.of(new SessionCredential(id, mac))
            : Optional.empty();
    }
}
