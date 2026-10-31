package com.saathi.order.web;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.order.service.MerchantActions;
import com.saathi.order.service.MerchantOrders;
import com.saathi.order.service.OrderViews;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.Page;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.6's merchant order surface: the queue, one order in it, and the shop's own edges.
 *
 * <p>Every POST is an action and none of them takes a status. The path is the verb — {@code /accept},
 * {@code /weigh}, {@code /dispatch-self} — and §10.7's rule that a client never supplies a state is
 * what keeps the transition table in one place: the answer to "what may this order do now" is
 * {@code allowed_actions} on the read, not a field on the write.
 *
 * <p>Most of these bodies are empty, and the ones that are not carry a reason. That asymmetry is the
 * document's: the shop presses a button for T6 and T11, and has to say why for T4, T7, T10, T17 and
 * §6.2's reselect, because those are the edges the customer will ask about (§13.2's ⚑ actions).
 *
 * <p>{@code /delivered-self} and {@code /handover-confirm} are absent on purpose. T16 accrues the
 * merchant payable and the commission receivable (§9.4) and handover confirms a rider's collection;
 * both belong to the modules that own accruals and jobs, and a route that answered 200 without those
 * rows would be a settlement bug wearing a working URL.
 */
@RestController
@RequestMapping("/api/v1/m/orders")
@RequiredArgsConstructor
public class MerchantOrderController {

    private final MerchantOrders queue;
    private final MerchantActions actions;

    @GetMapping
    public Page<OrderViews.Summary> list(
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "ward", required = false) String ward,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit,
        AuthenticatedPrincipal caller) {
        return queue.list(caller, status, ward, cursor, limit);
    }

    @GetMapping("/{code}")
    public OrderViews.Detail detail(@PathVariable("code") String code,
                                    AuthenticatedPrincipal caller) {
        return queue.detail(caller, code);
    }

    @PostMapping("/{code}/accept")
    public OrderViews.Detail accept(@PathVariable("code") String code,
                                    AuthenticatedPrincipal caller) {
        return actions.accept(caller, code, requestId());
    }

    @PostMapping("/{code}/reject")
    public OrderViews.Detail reject(@PathVariable("code") String code,
                                    @RequestBody(required = false) ReasonRequest body,
                                    AuthenticatedPrincipal caller) {
        return actions.reject(caller, code, body == null ? null : body.reason(), requestId());
    }

    @PostMapping("/{code}/prepare")
    public OrderViews.Detail prepare(@PathVariable("code") String code,
                                     AuthenticatedPrincipal caller) {
        return actions.prepare(caller, code, requestId());
    }

    @PostMapping("/{code}/weigh")
    public OrderViews.WithQuote weigh(@PathVariable("code") String code,
                                      @RequestBody(required = false) MerchantActions.WeighBody body,
                                      AuthenticatedPrincipal caller) {
        return actions.weigh(caller, code, body, requestId());
    }

    @PostMapping("/{code}/fulfilment-mode")
    public OrderViews.WithQuote fulfilmentMode(@PathVariable("code") String code,
                                              @RequestBody(required = false)
                                              MerchantActions.ModeBody body,
                                              AuthenticatedPrincipal caller) {
        return actions.reselectFulfilmentMode(caller, code, body, requestId());
    }

    @PostMapping("/{code}/ready")
    public OrderViews.Detail ready(@PathVariable("code") String code,
                                   AuthenticatedPrincipal caller) {
        return actions.markReady(caller, code, requestId());
    }

    @PostMapping("/{code}/dispatch-self")
    public OrderViews.Detail dispatchSelf(@PathVariable("code") String code,
                                          AuthenticatedPrincipal caller) {
        return actions.dispatchSelf(caller, code, requestId());
    }

    @PostMapping("/{code}/fail-delivery")
    public OrderViews.Detail failDelivery(@PathVariable("code") String code,
                                          @RequestBody(required = false) ReasonRequest body,
                                          AuthenticatedPrincipal caller) {
        return actions.failSelfDelivery(caller, code, body == null ? null : body.reason(),
            requestId());
    }

    @PostMapping("/{code}/cancel")
    public OrderViews.Detail cancel(@PathVariable("code") String code,
                                    @RequestBody(required = false) ReasonRequest body,
                                    AuthenticatedPrincipal caller) {
        return actions.cancel(caller, code, body == null ? null : body.reason(), requestId());
    }

    /**
     * The one body shape the shop's reason-bearing edges share. §6.3 requires a reason at T4, T10 and
     * T17 and each names its own closed enum, so the field is a string here and the vocabulary check
     * is the service's — where the enum it must match is already in scope.
     */
    public record ReasonRequest(String reason) {
    }

    private static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }
}
