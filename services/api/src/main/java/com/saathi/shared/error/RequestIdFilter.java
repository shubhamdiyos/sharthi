package com.saathi.shared.error;

import com.saathi.shared.id.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation (§10.1): every response carries {@code X-Request-Id}, every error repeats it,
 * and every log line and audit row for that request can be joined on it.
 *
 * <p>A client-supplied value is honoured only when it is a well-formed ULID, so the header
 * cannot be used to inject arbitrary text into structured logs.
 */
@Component
@Order(RequestIdFilter.ORDER)
@RequiredArgsConstructor
public class RequestIdFilter extends OncePerRequestFilter {

    /** Runs first, so a request id is in the MDC before anything else can log or be refused. */
    public static final int ORDER = 0;

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    /** Injected, so a test can mint a request id at a chosen millisecond (§10.1 correlation). */
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && Ulid.isValid(incoming) ? incoming : Ulid.next(clock);
        MDC.put(MDC_KEY, requestId);
        try {
            response.setHeader(HEADER, requestId);
            chain.doFilter(request, response);
        } finally {
            // The container reuses this thread, so an id left in the MDC would be printed
            // against somebody else's request.
            MDC.remove(MDC_KEY);
        }
    }
}
