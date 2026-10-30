package com.saathi.shared.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saathi.shared.id.Ulid;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * §10.1's correlation, which is the mechanism every later "who did this, and what did the
 * server think" question depends on. The header is both an output and an untrusted input, so
 * the interesting case is the client that sends one.
 */
class RequestIdFilterTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);

    private final RequestIdFilter filter = new RequestIdFilter(CLOCK);

    private MockHttpServletResponse run(MockHttpServletRequest request, boolean throwDownstream)
        throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> seenInsideChain = new java.util.ArrayList<>();
        jakarta.servlet.FilterChain chain = (req, res) -> {
            seenInsideChain.add(MDC.get(RequestIdFilter.MDC_KEY));
            if (throwDownstream) {
                throw new IllegalStateException("the controller exploded");
            }
        };
        try {
            filter.doFilter(request, response, chain);
        } finally {
            assertEquals(1, seenInsideChain.size(), "the chain ran exactly once");
            assertNull(MDC.get(RequestIdFilter.MDC_KEY), "and the thread came back clean");
        }
        return response;
    }

    @Test
    void aRequestWithNoHeaderGetsOneAndTheLogsSeeIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse response = run(request, false);

        String issued = response.getHeader(RequestIdFilter.HEADER);
        assertNotNull(issued);
        assertTrue(Ulid.isValid(issued), "minted ids are ULIDs: " + issued);
    }

    @Test
    void aWellFormedClientIdIsHonouredSoTheCallerCanJoinItsOwnLogs() throws Exception {
        String client = Ulid.next(CLOCK);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.addHeader(RequestIdFilter.HEADER, client);
        assertEquals(client, run(request, false).getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void anIllFormedClientIdIsReplacedRatherThanEchoed() throws Exception {
        // Two reasons, both real: an arbitrary string in a structured log field is a log
        // injection, and a forged id destroys the audit trail's only reliable join key.
        for (String hostile : List.of(
            "../../etc/passwd",
            "line1\nline2",
            "<script>",
            "01ARZ3NDEKTSV4RRFFQ69G5FA",           // 25 characters
            "01ARZ3NDEKTSV4RRFFQ69G5FAVEXTRA",     // 30 characters
            "01ARZ3NDEKTSV4RRFFQ69G5FAU",          // U is not in the alphabet
            "01arz3ndektsv4rrffq69g5fav",          // lower case is not canonical
            " ".repeat(26),
            "😀".repeat(13))) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
            request.addHeader(RequestIdFilter.HEADER, hostile);
            String returned = run(request, false).getHeader(RequestIdFilter.HEADER);
            assertNotEquals(hostile, returned, hostile);
            assertTrue(Ulid.isValid(returned), "replaced with a minted ULID: " + returned);
        }
    }

    @Test
    void theIdIsClearedEvenWhenTheControllerThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> run(request, true));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY),
            "a pooled thread that keeps an id prints it against the next request");
    }

    @Test
    void theErrorEnvelopeAndTheHeaderCarryTheSameValue() throws Exception {
        String client = Ulid.next(CLOCK);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/SAATH-0001");
        request.addHeader(RequestIdFilter.HEADER, client);
        String[] insideChain = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            insideChain[0] = MDC.get(RequestIdFilter.MDC_KEY);
            ErrorEnvelope.Detail detail = ErrorEnvelope
                .opaque(insideChain[0]).error();
            assertEquals(client, detail.requestId(), "the user can quote the id they were shown");
        });
        assertEquals(client, insideChain[0]);
    }

    @Test
    void theAdviceAnswersUnknownWhenThereIsNoRequestContext() {
        // A background job reaching the same advice must not print "null" where an id belongs.
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        ErrorEnvelope.Detail detail = new ApiAdvice()
            .handleApiException(new ApiException(ErrorCode.NOT_FOUND, "Not found in the caller's scope."))
            .getBody().error();
        assertEquals("unknown", detail.requestId());
        assertEquals("NOT_FOUND", detail.code());
    }
}
