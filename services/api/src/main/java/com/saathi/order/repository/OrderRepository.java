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
