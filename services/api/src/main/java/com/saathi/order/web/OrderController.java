package com.saathi.order.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.order.service.OrderIntake;
import com.saathi.order.service.OrderQueryService;
import com.saathi.order.service.OrderViews;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.5: the customer's orders.
 *
 * <p>{@code POST /orders} answers with the writer's own JSON because the replay must return the
 * exact bytes of the original 201, not a re-render that could differ after a catalogue edit — the
 * replayed body is part of the idempotency contract, not a convenience.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OrderController {

    private final OrderIntake intake;
    private final OrderQueryService orders;

    @PostMapping(path = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> place(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody OrderIntake.Body body,
                                        AuthenticatedPrincipal caller) {
        OrderIntake.Result result = intake.place(caller, idempotencyKey, body, requestId());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
            .contentType(MediaType.APPLICATION_JSON);
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.bodyJson());
    }

    @GetMapping("/orders")
    public Page<OrderViews.Summary> list(
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit,
        AuthenticatedPrincipal caller) {
        return orders.list(caller, status, cursor, limit);
    }

    @GetMapping("/orders/{code}")
    public OrderViews.Detail detail(@PathVariable("code") String code,
                                    AuthenticatedPrincipal caller) {
        return orders.detail(caller, code);
    }

    @GetMapping("/orders/{code}/timeline")
    public OrderViews.Timeline timeline(@PathVariable("code") String code,
                                        AuthenticatedPrincipal caller) {
        return orders.timeline(caller, code);
    }

    @PostMapping("/orders/{code}/cancel")
    public OrderViews.Detail cancel(@PathVariable("code") String code,
                                    @Valid @RequestBody(required = false) CancelRequest body,
                                    AuthenticatedPrincipal caller) {
        return orders.cancel(caller, code, body == null ? null : body.reason(), requestId());
    }

    /** T5's reason is optional; §6.3 requires one only for the shop's and the operator's cancels. */
    public record CancelRequest(@Size(max = 300) String reason) {
    }

    private static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }
}
