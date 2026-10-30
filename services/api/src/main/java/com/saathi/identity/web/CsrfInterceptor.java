package com.saathi.identity.web;

import com.saathi.identity.security.IdentityKeys;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.identity.session.SessionAuthFilter;
import com.saathi.identity.session.SessionCredential;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The synchroniser-token half of §12.4's mutation rule.
 *
 * <p>§10.1 requires {@code X-CSRF-Token} on every mutation, and taken literally that would reject
 * the two endpoints whose entire job is to create a session. The check is therefore gated on a live
 * principal: a request that carries an unusable cookie has nothing to protect, and a request that
 * carries a <em>usable</em> one does — which is what closes login CSRF. An attacker's page cannot
 * make the victim's browser send the header, and cannot read a token minted from the victim's
 * session id, so a mutation arriving with a resolved session and no matching header is refused.
 *
 * <p>{@code FORBIDDEN} rather than {@code UNAUTHENTICATED}: the caller is known, the request is not.
 * Throwing instead of writing a status here keeps one implementation of the §10.2 envelope, the same
 * reason {@link com.saathi.identity.session.SessionAuthFilter} answers nothing.
 */
@Component
@RequiredArgsConstructor
public class CsrfInterceptor implements HandlerInterceptor {

    private final IdentityKeys keys;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isSafe(request.getMethod())) {
            return true;
        }
        if (!(request.getAttribute(SessionAuthFilter.REQUEST_ATTRIBUTE) instanceof AuthenticatedPrincipal caller)) {
            return true;
        }
        String presented = request.getHeader(SessionCredential.CSRF_HEADER);
        if (presented != null && keys.csrfTokenMatches(caller.sessionId(), presented)) {
            return true;
        }
        throw new ApiException(ErrorCode.FORBIDDEN, "This request needs the X-CSRF-Token header.");
    }

    /**
     * {@code SafeMethodInterceptor} in Spring Security treats TRACE as safe; it is not routed here
     * at all, and a method the server does not implement cannot change state whatever this decides.
     */
    private static boolean isSafe(String method) {
        return switch (method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }
}
