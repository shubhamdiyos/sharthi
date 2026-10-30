package com.saathi.catalog.web;

import com.saathi.catalog.domain.Product;
import com.saathi.catalog.service.ProductService;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.6's catalogue endpoints: the merchant's own items, addressed only through the shop the
 * session is bound to.
 *
 * <p>There is no {@code merchant} in any path or body. That is §11.1's rule stated as a URL: the
 * scope comes from the grant bound at login, so a member of one shop cannot name another by
 * changing a string, and the endpoints that resolve it answer 404 rather than guessing.
 */
@RestController
@RequestMapping("/api/v1/m/products")
@RequiredArgsConstructor
public class MerchantProductController {

    private final ProductService products;
    private final RequestTags tags;

    @GetMapping
    public Page<CatalogViews.ProductView> list(
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit,
        AuthenticatedPrincipal caller) {
        return products.list(caller, state, q, cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogViews.ProductView create(@Valid @RequestBody ProductService.Create body,
                                           AuthenticatedPrincipal caller,
                                           HttpServletRequest request) {
        Product product = products.create(caller, body, requestId(), ipHash(request));
        return products.view(product);
    }

    /**
     * {@code If-Match: version} is mandatory. Without it two tabs editing the same item would
     * each write the whole row and the second would silently undo the first; §10.6's optimistic
     * lock is the cheap answer and this endpoint makes its absence an error rather than a race.
     */
    @PatchMapping("/{code}")
    public CatalogViews.ProductView update(
        @PathVariable("code") String code,
        @RequestHeader(name = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody ProductService.Update body,
        AuthenticatedPrincipal caller,
        HttpServletRequest request) {
        Product product = products.update(caller, code, body, parseVersion(ifMatch),
            requestId(), ipHash(request));
        return products.view(product);
    }

    @PostMapping("/{code}/availability")
    public CatalogViews.ProductView availability(@PathVariable("code") String code,
                                                 @RequestBody AvailabilityRequest body,
                                                 AuthenticatedPrincipal caller,
                                                 HttpServletRequest request) {
        if (body == null || body.available() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "available", "message", "is required"))));
        }
        Product product = products.setAvailability(caller, code, body.available(),
            requestId(), ipHash(request));
        return products.view(product);
    }

    @PostMapping("/{code}/deactivate")
    public CatalogViews.ProductView deactivate(@PathVariable("code") String code,
                                               AuthenticatedPrincipal caller,
                                               HttpServletRequest request) {
        Product product = products.deactivate(caller, code, requestId(), ipHash(request));
        return products.view(product);
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "If-Match",
                    "message", "is required; send the product's current version"))));
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "If-Match",
                    "message", "is not a version"))));
        }
    }

    private static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    private String ipHash(HttpServletRequest request) {
        return tags.of(request).ipHash();
    }

    /** The toggle's own shape: one boolean, so a typo cannot silently mean {@code false}. */
    public record AvailabilityRequest(Boolean available) {
    }
}
