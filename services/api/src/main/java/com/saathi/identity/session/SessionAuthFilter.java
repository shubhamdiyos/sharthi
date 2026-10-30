package com.saathi.identity.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the session cookie into a request attribute and nothing else.
 *
 * <p>It deliberately answers no status code. A filter that writes its own 401 has to duplicate the
 * §10.2 envelope by hand — the exception advice has not run yet — and a second implementation of an
 * error shape is a second shape that can drift. So an unusable cookie simply leaves the request
 * anonymous here, and the {@code CurrentUserArgumentResolver} raises
 * {@code UNAUTHENTICATED} through {@code ApiAdvice} when an endpoint actually needed a caller.
 *
 * <p>That also means an expired or revoked session is never rejected on sight: {@code GET /cities}
 * with a dead cookie is a public read that works. The cost is one database read per request that
 * carries a cookie, which is §12.4's own requirement rather than this filter's invention.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Where the resolved caller is parked for the resolver and the CSRF interceptor to find. */
    public static final String REQUEST_ATTRIBUTE = "saathi.principal";

    private final SessionService sessions;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        cookie(request).flatMap(sessions::resolve)
            .ifPresent(principal -> request.setAttribute(REQUEST_ATTRIBUTE, principal));
        chain.doFilter(request, response);
    }

    private static Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SessionCredential.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
