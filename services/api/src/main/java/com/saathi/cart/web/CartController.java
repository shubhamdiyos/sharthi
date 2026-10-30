package com.saathi.cart.web;

import com.saathi.cart.service.CartService;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.quote.service.QuoteViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.4: the basket, and the quote that turns it into a total.
 *
 * <p>Every path here is the caller's own basket — there is no customer id in any of them. The
 * ward and the shop come from the session and the first line respectively, so a body can describe
 * goods and quantities and nothing else (§10.7).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CartController {

    private final CartService carts;

    @GetMapping("/cart")
    public CartViews.CartView cart(AuthenticatedPrincipal caller) {
        return carts.view(caller);
    }

    @PostMapping("/cart/lines")
    public CartViews.CartView addLine(@Valid @RequestBody AddLineRequest body,
                                      AuthenticatedPrincipal caller) {
        return carts.addLine(caller, new CartService.AddLine(body.productCode(), body.qty()));
    }

    @PatchMapping("/cart/lines/{id}")
    public CartViews.CartView changeQty(@PathVariable("id") String id,
                                        @Valid @RequestBody QtyRequest body,
                                        AuthenticatedPrincipal caller) {
        return carts.changeQty(caller, id, new CartService.ChangeQty(body.qty()));
    }

    @DeleteMapping("/cart/lines/{id}")
    public CartViews.CartView removeLine(@PathVariable("id") String id,
                                         AuthenticatedPrincipal caller) {
        return carts.removeLine(caller, id);
    }

    @PostMapping("/cart/clear")
    public CartViews.CartView clear(AuthenticatedPrincipal caller) {
        return carts.clear(caller);
    }

    /** §10.5.4's ward change: every fee is re-quoted and the previous total is superseded. */
    @PutMapping("/cart/ward")
    public CartViews.CartView changeWard(@Valid @RequestBody WardRequest body,
                                         AuthenticatedPrincipal caller) {
        return carts.changeWard(caller, new CartService.ChangeWard(body.wardNo()));
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteViews.QuoteView issueQuote(@RequestBody IssueQuoteRequest body,
                                           AuthenticatedPrincipal caller) {
        return carts.issueQuote(caller, new CartService.IssueQuote(
            body.scope(), body.cartId(), body.orderId(), body.fulfilmentMode()));
    }

    @GetMapping("/quotes/{id}")
    public QuoteViews.QuoteView quote(@PathVariable("id") String id,
                                      AuthenticatedPrincipal caller) {
        return carts.quoteView(caller, id);
    }

    /** A product is named the way the storefront names it: its public code (§4.3-6). */
    public record AddLineRequest(
        @NotBlank @Size(max = 120) String productCode,
        @NotNull @DecimalMin(value = "0.001") BigDecimal qty) {
    }

    public record QtyRequest(@NotNull @Positive BigDecimal qty) {
    }

    public record WardRequest(@NotNull @Positive Integer wardNo) {
    }

    /** {@code scope} defaults to {@code CART}; order scopes are issued by the order surface. */
    public record IssueQuoteRequest(
        String scope,
        String cartId,
        String orderId,
        String fulfilmentMode) {
    }
}
