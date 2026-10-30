package com.saathi.identity.session;

import com.saathi.shared.config.SaathiProperties;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The two cookies §12.4 names, written the same way everywhere.
 *
 * <p>{@code saathi_session} is HttpOnly: a script that can read it can replay it, which is the whole
 * argument for HttpOnly. {@code saathi_csrf} is deliberately readable, because the page has to copy
 * it into the {@code X-CSRF-Token} header — and it is only a CSRF token, worthless without the
 * session cookie beside it, so readability costs nothing the session cookie does not already cover.
 *
 * <p>{@code Secure} follows the deployment rather than {@code request.isSecure()}: behind a proxy
 * that terminates TLS, the request looks insecure on a site that is only ever served over HTTPS, and
 * a cookie silently dropped to non-secure because somebody forgot {@code X-Forwarded-Proto} is a
 * downgrade nobody would notice. Staging and production are HTTPS-only by decision (§2.2).
 */
@Component
@RequiredArgsConstructor
public class SessionCookies {

    /** Long enough for a page load to become a mutation; short because it is not a credential. */
    private static final Duration CSRF_LIFETIME = Duration.ofDays(1);

    private final SaathiProperties properties;
    private final Clock clock;

    public void issue(HttpHeaders headers, SessionService.IssuedSession session) {
        Duration remaining = Duration.between(clock.instant(), session.expiresAt());
        headers.add(HttpHeaders.SET_COOKIE,
            build(SessionCredential.COOKIE_NAME, session.cookieValue(), remaining, true));
        headers.add(HttpHeaders.SET_COOKIE,
            build(SessionCredential.CSRF_COOKIE_NAME, session.csrfToken(), CSRF_LIFETIME, false));
    }

    public void clear(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, build(SessionCredential.COOKIE_NAME, "", Duration.ZERO, true));
        headers.add(HttpHeaders.SET_COOKIE, build(SessionCredential.CSRF_COOKIE_NAME, "", Duration.ZERO, false));
    }

    private String build(String name, String value, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(!properties.isDevelopment())
            .sameSite("Lax")
            .path("/")
            // A zero or negative Max-Age is the delete instruction. This is the only lifetime the
            // builder can write — there is no Expires attribute on ResponseCookie — which is fine
            // under RFC 6265, where Max-Age takes precedence, and under §12.4, where the row rather
            // than the cookie decides when access ends.
            .maxAge(maxAge.isNegative() || maxAge.isZero() ? Duration.ZERO : maxAge)
            .build()
            .toString();
    }
}
