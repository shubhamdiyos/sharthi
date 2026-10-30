package com.saathi.it;

import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.shared.tenant.TenantScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that exist only so the §10.2 contract can be tested over a socket.
 *
 * <p>They live in test sources and are enabled only under the {@code test} profile, so they can
 * never reach a real deployment: each one raises exactly one failure so the wire shape of every
 * status code in the table is asserted rather than assumed. This is also the first place the
 * snake_case naming strategy is proven, because it is proven on bytes.
 *
 * <p>No {@code @Validated} on the type: since Framework 6.1 a controller's parameter constraints
 * are validated by the built-in handler-method mechanism, and adding the annotation back would
 * route failures through the AOP proxy instead — a different exception, and a different 422 body —
 * from the one every later module will actually produce.
 */
@Profile("test")
@RestController
class ProbeController {

    /** Fixed so a probe row's id is reproducible in a failing test's output. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);

    record ProbeView(boolean probeOk, String requestId) {
    }

    /** The component names are camelCase on purpose: the wire names are not, and the 422 must say so. */
    record ProbeOrder(@NotBlank @Size(max = 40) String publicCode, @Min(1) int lineCount) {
    }

    /**
     * Reads the request id out of the MDC, not out of the header. {@code RequestIdFilter} only
     * honours an incoming header when it is a well-formed ULID, so the header is client-controlled
     * text and the MDC value is the platform's; an endpoint that echoes the raw header would hand
     * a caller a write into someone's log line.
     */
    @GetMapping("/it-probe/ping")
    ProbeView ping() {
        return new ProbeView(true, MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping("/it-probe/not-found")
    ProbeView notFound() {
        throw new ApiException(ErrorCode.NOT_FOUND, "Not found in the caller's scope.");
    }

    /** §4.3-5: a read of another tenant's row answers 404, never 403. */
    @GetMapping("/it-probe/cross-tenant")
    ProbeView crossTenant() {
        scope("order:read").requireSameTenant(Ulid.next(CLOCK));
        throw new IllegalStateException("unreachable");
    }

    @GetMapping("/it-probe/forbidden")
    ProbeView forbidden() {
        scope().require("order:read");
        throw new IllegalStateException("unreachable");
    }

    /** Permission resolved, scope refused: a different code, and it names no resource. */
    @GetMapping("/it-probe/scope-violation")
    ProbeView scopeViolation() {
        scope().requireScopeGranted(false);
        throw new IllegalStateException("unreachable");
    }

    @GetMapping("/it-probe/invalid-transition")
    ProbeView invalidTransition() {
        throw new ApiException(ErrorCode.INVALID_TRANSITION, "That transition is not allowed from the current state.");
    }

    @GetMapping("/it-probe/validated")
    ProbeView validated(@RequestParam(required = false) @NotBlank @Size(max = 8) String q,
                        @RequestParam(defaultValue = "0") @Min(1) int limit) {
        return new ProbeView(true, q + ":" + limit);
    }

    @PostMapping("/it-probe/orders")
    ProbeOrder create(@Valid @RequestBody ProbeOrder order) {
        return order;
    }

    /**
     * The message here is written to look like a leak — a jdbc url with a password in it — so the
     * test that asserts it never reaches the client is testing something worth asserting.
     */
    @GetMapping("/it-probe/boom")
    ProbeView boom() {
        throw new IllegalStateException("jdbc:postgresql://saathi:hunter2@postgres:5432/saathi could not be opened");
    }

    private static TenantScope scope(String... permissions) {
        return new TenantScope(Ulid.next(CLOCK), Ulid.next(CLOCK), PrincipalKind.OPERATOR, Set.of(permissions));
    }
}
