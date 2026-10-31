package com.saathi.order.repository;

import com.saathi.order.domain.Order;
import com.saathi.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Order rows. The customer's list is keyset-paged on {@code (placed_at DESC, id DESC)} (§10.3). */
public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByCode(String code);

    Optional<Order> findByTenantIdAndCustomerIdAndCode(String tenantId, String customerId,
                                                       String code);

    Optional<Order> findByIdAndTenantId(String id, String tenantId);

    /**
     * A shop's own order, by the public code. There is no {@code merchantId} in any merchant request
     * body or path (§10.7), so this is the only way the queue resolves one: an order belonging to
     * another shop reads as absent, which is §10.2's {@code NOT_FOUND} for a cross-merchant read.
     */
    Optional<Order> findByTenantIdAndMerchantIdAndCode(String tenantId, String merchantId,
                                                       String code);

    /**
     * The merchant queue (§10.5.6), paged on the same {@code (placed_at DESC, id DESC)} keyset as the
     * customer's list and served by {@code order_merchant_queue}. {@code wardId} empty means "every
     * ward this shop serves" — an empty string rather than null, because the null bind of an
     * {@code @Enumerated} sibling already proved that Hibernate reads a bare null parameter as an
     * untyped literal.
     */
    @Query("""
        select o from Order o
         where o.tenantId = :tenantId
           and o.merchantId = :merchantId
           and (:status is null or o.status = :status)
           and (:wardId = '' or o.wardId = :wardId)
           and (:filtered = false
                or o.placedAt < :afterAt
                or (o.placedAt = :afterAt and o.id < :afterId))
         order by o.placedAt desc, o.id desc
        """)
    List<Order> merchantOrders(
        @Param("tenantId") String tenantId,
        @Param("merchantId") String merchantId,
        @Param("status") OrderStatus status,
        @Param("wardId") String wardId,
        @Param("filtered") boolean filtered,
        @Param("afterAt") Instant afterAt,
        @Param("afterId") String afterId,
        Pageable pageable);

    /**
     * The status parameter is the enum, not a bare string: {@code o.status = :status} on an
     * {@code @Enumerated(STRING)} path cannot bind a String, and Hibernate fails the query before
     * it ever reaches Postgres.
     */
    @Query("""
        select o from Order o
         where o.tenantId = :tenantId
           and o.customerId = :customerId
           and (:status is null or o.status = :status)
           and (:filtered = false
                or o.placedAt < :afterAt
                or (o.placedAt = :afterAt and o.id < :afterId))
         order by o.placedAt desc, o.id desc
        """)
    List<Order> customerOrders(
        @Param("tenantId") String tenantId,
        @Param("customerId") String customerId,
        @Param("status") OrderStatus status,
        @Param("filtered") boolean filtered,
        @Param("afterAt") Instant afterAt,
        @Param("afterId") String afterId,
        Pageable pageable);
}
