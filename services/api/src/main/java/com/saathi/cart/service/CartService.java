package com.saathi.cart.service;

import com.saathi.cart.domain.Cart;
import com.saathi.cart.domain.CartLine;
import com.saathi.cart.repository.CartLineRepository;
import com.saathi.cart.repository.CartRepository;
import com.saathi.cart.web.CartViews;
import com.saathi.catalog.domain.Product;
import com.saathi.catalog.service.CatalogShelf;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.service.MerchantDirectory;
import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.service.QuoteService;
import com.saathi.quote.service.QuoteSnapshot;
import com.saathi.quote.service.QuoteViews;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.service.ServiceabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.5.4's basket: one shop, one ward, and the quote that turns it into a number.
 *
 * <p>The city and ward come from the session (§10.1), never from the request: a customer cannot
 * add a Siwan item to a Mashaun basket by passing a different city. The shop is fixed by the first
 * line (§8.4) and every later line is checked against it, because a basket spanning two shops is
 * something the platform cannot deliver or price.
 *
 * <p>Every mutation supersedes the live quote. That is the rule that makes {@code GET /cart}'s
 * {@code active_quote} trustworthy: if it is present, it describes exactly the lines above it.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository carts;
    private final CartLineRepository lines;
    private final CatalogShelf shelf;
    private final MerchantDirectory directory;
    private final ServiceabilityService serviceability;
    private final CartPricing pricing;
    private final QuoteService quotes;
    private final Clock clock;

    public record AddLine(String productCode, BigDecimal qty) {
    }

    public record ChangeQty(BigDecimal qty) {
    }

    public record ChangeWard(Integer wardNo) {
    }

    public record IssueQuote(String scope, String cartId, String orderId, String fulfilmentMode) {
    }

    private record Context(Tenant tenant, ServiceWard ward) {
    }

    @Transactional(readOnly = true)
    public CartViews.CartView view(AuthenticatedPrincipal caller) {
        Context ctx = context(caller);
        Cart cart = carts.findByTenantIdAndCustomerId(ctx.tenant().getId(), caller.accountId())
            .orElse(null);
        return render(ctx, cart);
    }

    @Transactional
    public CartViews.CartView addLine(AuthenticatedPrincipal caller, AddLine body) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        Product product = shelf.byCode(body.productCode());
        if (!ctx.tenant().getId().equals(product.getTenantId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that item.");
        }

        Cart cart = carts.findByTenantIdAndCustomerId(ctx.tenant().getId(), caller.accountId())
            .orElse(null);
        CartLine existing = cart == null ? null
            : lines.findByCartIdAndProductId(cart.getId(), product.getId()).orElse(null);
        BigDecimal combined = body.qty()
            .add(existing == null ? BigDecimal.ZERO : existing.getQty());
        CartPricing.requireSellable(product, combined);

        if (cart == null) {
            cart = newCart(ctx, caller);
        } else if (cart.getMerchantId() != null
            && !cart.getMerchantId().equals(product.getMerchantId())) {
            throw mixedMerchant(ctx, cart, product);
        }

        Instant now = clock.instant();
        if (cart.getMerchantId() == null) {
            cart.setMerchantId(product.getMerchantId());
        }
        cart.setUpdatedAt(now);
        carts.saveAndFlush(cart);

        if (existing == null) {
            lines.save(CartLine.builder()
                .id(Ulid.next(clock))
                .tenantId(cart.getTenantId())
                .cartId(cart.getId())
                .productId(product.getId())
                .qty(body.qty())
                // The price shown when it was added; §8.2 compares it with today's price.
                .unitPricePaiseAtAdd(product.getPricePaise())
                .addedAt(now)
                .build());
        } else {
            existing.setQty(combined);
            lines.save(existing);
        }
        quotes.supersedeCart(cart.getId());
        return render(ctx, cart);
    }

    @Transactional
    public CartViews.CartView changeQty(AuthenticatedPrincipal caller, String lineId,
                                        ChangeQty body) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        Cart cart = ownedCart(ctx, caller);
        CartLine line = lines.findByIdAndCartId(lineId, cart.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That item is not in your cart."));
        Product product = shelf.byIds(cart.getTenantId(), List.of(line.getProductId())).stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.STOCK_CHANGED,
                "One of the items in your cart is no longer available."));
        CartPricing.requireSellable(product, body.qty());

        line.setQty(body.qty());
        lines.save(line);
        cart.setUpdatedAt(clock.instant());
        carts.save(cart);
        quotes.supersedeCart(cart.getId());
        return render(ctx, cart);
    }

    @Transactional
    public CartViews.CartView removeLine(AuthenticatedPrincipal caller, String lineId) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        Cart cart = ownedCart(ctx, caller);
        CartLine line = lines.findByIdAndCartId(lineId, cart.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That item is not in your cart."));
        lines.delete(line);
        lines.flush();
        if (lines.countByCartId(cart.getId()) == 0) {
            cart.setMerchantId(null);
        }
        cart.setUpdatedAt(clock.instant());
        carts.save(cart);
        quotes.supersedeCart(cart.getId());
        return render(ctx, cart);
    }

    @Transactional
    public CartViews.CartView clear(AuthenticatedPrincipal caller) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        Cart cart = carts.findByTenantIdAndCustomerId(ctx.tenant().getId(), caller.accountId())
            .orElse(null);
        if (cart == null) {
            return CartViews.empty(ctx.ward());
        }
        lines.deleteByCartId(cart.getId());
        lines.flush();
        cart.setMerchantId(null);
        cart.setUpdatedAt(clock.instant());
        carts.save(cart);
        quotes.supersedeCart(cart.getId());
        return render(ctx, cart);
    }

    @Transactional
    public CartViews.CartView changeWard(AuthenticatedPrincipal caller, ChangeWard body) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        ServiceWard ward = serviceability.servedWard(ctx.tenant(), body.wardNo());
        Cart cart = carts.findByTenantIdAndCustomerId(ctx.tenant().getId(), caller.accountId())
            .orElse(null);
        if (cart == null) {
            cart = newCart(new Context(ctx.tenant(), ward), caller);
        } else if (!ward.getId().equals(cart.getWardId())) {
            cart.setWardId(ward.getId());
            cart.setUpdatedAt(clock.instant());
            carts.save(cart);
            // §10.5.4: a ward change re-quotes every fee, so the previous total stops meaning
            // anything the moment this commits.
            quotes.supersedeCart(cart.getId());
        }
        return render(new Context(ctx.tenant(), ward), cart);
    }

    /**
     * {@code POST /quotes} — the only way to get a total (§10.5.4). Day 1's customer flow issues
     * {@code CART} quotes; the {@code WEIGHT} scope belongs to the merchant's weighing step and is
     * refused here rather than silently mis-scoped.
     */
    @Transactional
    public QuoteViews.QuoteView issueQuote(AuthenticatedPrincipal caller, IssueQuote body) {
        caller.require("CART_WRITE");
        Context ctx = context(caller);
        String scope = body.scope() == null || body.scope().isBlank()
            ? "CART" : body.scope().trim().toUpperCase(Locale.ROOT);
        if (!scope.equals("CART")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "Only cart quotes are issued here; an order's price quote comes from the shop.");
        }
        Cart cart = ownedCart(ctx, caller);
        ServiceWard ward = cartWard(ctx, cart);
        CartPricing.Priced priced = pricing.price(ctx.tenant(), cart, ward,
            body.fulfilmentMode(), clock.instant());
        QuoteSnapshot snapshot = quotes.issue(priced.input());
        return quotes.render(snapshot, wardRef(ward));
    }

    /**
     * {@code GET /quotes/{id}} — the consent artifact, re-renderable (§10.5.4). A quote that has
     * expired is expired on this read and then refused (§8.2).
     */
    public QuoteViews.QuoteView quoteView(AuthenticatedPrincipal caller, String quoteId) {
        Context ctx = context(caller);
        QuoteSnapshot snapshot = quotes.read(ctx.tenant().getId(), quoteId);
        PriceQuote quote = snapshot.quote();
        if (quote.getCartId() == null) {
            // Order-scoped quotes (WEIGHT, FULFILMENT_CHANGE) belong to the order surface that
            // issues them; their ownership is an order's, not a cart's.
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote.");
        }
        Cart cart = carts.findByIdAndTenantId(quote.getCartId(), ctx.tenant().getId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote."));
        if (!cart.getCustomerId().equals(caller.accountId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "We could not find that quote.");
        }
        return quotes.render(snapshot, wardRef(cartWard(ctx, cart)));
    }

    /**
     * The basket a placement is about to consume. Exposed for {@code order/}: the order copies
     * the cart's lines into its own snapshot and clears the basket in the same transaction.
     */
    @Transactional(readOnly = true)
    public Cart cartFor(String tenantId, String customerId) {
        return carts.findByTenantIdAndCustomerId(tenantId, customerId)
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Your cart is empty."));
    }

    /**
     * The cart after a placement, in the same transaction as the order (§10.6.1): goods that were
     * ordered must not remain in a basket the customer can accidentally order again.
     */
    @Transactional
    public void clearAfterOrder(String tenantId, String cartId) {
        Cart cart = carts.findByIdAndTenantId(cartId, tenantId)
            .orElseThrow(() -> new IllegalStateException("order names a cart that does not exist"));
        lines.deleteByCartId(cart.getId());
        lines.flush();
        cart.setMerchantId(null);
        cart.setUpdatedAt(clock.instant());
        carts.save(cart);
        quotes.supersedeCart(cart.getId());
    }

    private CartViews.CartView render(Context ctx, Cart cart) {
        if (cart == null) {
            return CartViews.empty(ctx.ward());
        }
        ServiceWard ward = cartWard(ctx, cart);
        List<CartLine> rows = lines.findByCartIdOrderByAddedAtAsc(cart.getId());
        List<Product> products = shelf.byIds(cart.getTenantId(),
            rows.stream().map(CartLine::getProductId).toList());
        Merchant merchant = cart.getMerchantId() == null ? null
            : directory.shopInTenant(cart.getTenantId(), cart.getMerchantId());
        QuoteViews.QuoteView active = quotes.activeCartQuote(cart.getId())
            .map(snapshot -> quotes.render(snapshot, wardRef(ward)))
            .orElse(null);
        return CartViews.of(cart, merchant, ward, products, rows, active);
    }

    private Cart ownedCart(Context ctx, AuthenticatedPrincipal caller) {
        return carts.findByTenantIdAndCustomerId(ctx.tenant().getId(), caller.accountId())
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Your cart is empty."));
    }

    private Cart newCart(Context ctx, AuthenticatedPrincipal caller) {
        Instant now = clock.instant();
        Cart cart = carts.saveAndFlush(Cart.builder()
            .id(Ulid.next(clock))
            .tenantId(ctx.tenant().getId())
            .customerId(caller.accountId())
            .wardId(ctx.ward().getId())
            .createdAt(now)
            .updatedAt(now)
            .build());
        return cart;
    }

    /** A cart keeps its own ward; a deactivated ward is refused rather than silently re-priced. */
    private ServiceWard cartWard(Context ctx, Cart cart) {
        return serviceability.wardById(ctx.tenant(), cart.getWardId());
    }

    private ApiException mixedMerchant(Context ctx, Cart cart, Product product) {
        Merchant current = directory.shopInTenant(ctx.tenant().getId(), cart.getMerchantId());
        Merchant added = directory.shopInTenant(ctx.tenant().getId(), product.getMerchantId());
        return new ApiException(ErrorCode.MIXED_MERCHANT_CART,
            "Your cart already has items from " + current.getTradeName() + ".",
            Map.of("cart_shop", current.getTradeName(), "added_shop", added.getTradeName()));
    }

    private Context context(AuthenticatedPrincipal caller) {
        if (caller.kind() != PrincipalKind.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "The basket belongs to a customer.");
        }
        if (caller.tenantId() == null || caller.tenantId().isBlank()
            || caller.wardId() == null || caller.wardId().isBlank()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA,
                "Choose your area before you shop.");
        }
        Tenant tenant = serviceability.liveTenantById(caller.tenantId());
        ServiceWard ward = serviceability.wardById(tenant, caller.wardId());
        return new Context(tenant, ward);
    }

    static QuoteViews.Ward wardRef(ServiceWard ward) {
        return new QuoteViews.Ward(ward.getWardNo(), ward.getName());
    }
}
