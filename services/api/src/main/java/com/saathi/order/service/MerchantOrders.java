package com.saathi.order.service;

import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderStatus;
import com.saathi.order.repository.OrderRepository;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import com.saathi.tenancy.domain.ServiceWard;
import com.saathi.tenancy.repository.ServiceWardRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.5.6's two merchant reads: the queue and one order in it.
 *
 * <p>The rendering is {@link OrderQueryService}'s, with {@link OrderViews.Viewer#SHOP}: a shop's
 * queue and a customer's list are the same rows seen through two entitlements, and the difference
 * between them is the {@code contacts} and the {@code allowed_actions} the view adds, not a second
 * shape a client has to reconcile.
 *
 * <p>Nothing here takes a merchant id. {@code GET /m/orders?ward=} names a ward <em>number</em>
 * because that is what a shop's board shows and what its screen was designed with; the id it resolves
 * to is this tenant's, and a ward number the city does not have is a broken filter rather than an
 * empty board.
 */
@Service
@RequiredArgsConstructor
public class MerchantOrders {

    private final OrderRepository orders;
    private final ServiceWardRepository wards;
    private final OrderScope scope;
    private final OrderQueryService reads;

    @Transactional(readOnly = true)
    public Page<OrderViews.Summary> list(AuthenticatedPrincipal caller, String status, String ward,
                                         String cursor, Integer limit) {
        String shopId = scope.shopOf(caller, "ORDER_READ_MERCHANT");
        String tenantId = caller.tenantId();
        int size = CursorPager.clampLimit(limit);
        OrderQueryService.After after = OrderQueryService.After.decode(cursor);
        OrderStatus wanted = OrderQueryService.statusFilter(status);
        String wardId = wardId(tenantId, ward);

        List<Order> rows = orders.merchantOrders(tenantId, shopId, wanted, wardId,
            after.filtered(), after.at(), after.id(),
            PageRequest.of(0, CursorPager.fetchSize(size)));
        boolean hasMore = rows.size() > size;
        List<Order> page = hasMore ? rows.subList(0, size) : rows;

        return Page.of(reads.summaries(page, OrderViews.Viewer.SHOP),
            OrderQueryService.After.next(page, hasMore), hasMore, size);
    }

    /** {@code GET /m/orders/{code}}: the order, its lines, and §11.4's contact for a shop. */
    @Transactional(readOnly = true)
    public OrderViews.Detail detail(AuthenticatedPrincipal caller, String code) {
        return reads.detailOf(scope.shopOrder(caller, "ORDER_READ_MERCHANT", code),
            OrderViews.Viewer.SHOP);
    }

    private String wardId(String tenantId, String ward) {
        if (ward == null || ward.isBlank()) {
            return "";
        }
        int wardNo;
        try {
            wardNo = Integer.parseInt(ward.trim());
        } catch (NumberFormatException e) {
            throw badWard(ward);
        }
        ServiceWard row = wards.findByTenantIdAndWardNo(tenantId, wardNo)
            .orElseThrow(() -> badWard(ward));
        return row.getId();
    }

    /**
     * Refused as {@code VALIDATION_FAILED} rather than answered with an empty page: a shop whose
     * board filter silently matched nothing would conclude it had no orders in that ward, which is a
     * wrong fact about the city rather than a wrong parameter.
     */
    private static ApiException badWard(String ward) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "ward is not one of this city's wards",
            Map.of("field_errors", List.of(Map.of("field", "ward", "message",
                "must be a ward number this city serves"))));
    }
}
