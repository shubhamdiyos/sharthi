package com.saathi.stock.repository;

import com.saathi.stock.domain.ReservationState;
import com.saathi.stock.domain.StockAdjustment;
import com.saathi.stock.domain.StockPolicy;
import com.saathi.stock.domain.StockReservation;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The stock module's own rows: reservations, policy overrides and adjustments.
 *
 * <p>The conditional update of {@code product.reserved_qty} is deliberately <em>not</em> here. That
 * column belongs to {@code catalog/}, and §3's rule is that a module reaches another only through
 * its service interface, so the statement lives in {@code StockBook} and this class calls nothing it
 * does not own.
 */
@Repository
@RequiredArgsConstructor
public class StockLedger {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public List<StockReservation> openReservations(String orderId) {
        return entityManager.createQuery("""
                select r from StockReservation r
                 where r.orderId = :orderId and r.state = :state
                 order by r.id asc
                """, StockReservation.class)
            .setParameter("orderId", orderId)
            .setParameter("state", ReservationState.HELD)
            .getResultList();
    }

    /** §7.2: tenant policy unless the shop overrides it. */
    public StockPolicy effectivePolicy(String tenantStockPolicy, String merchantId) {
        List<String> overrides = jdbc.queryForList(
            "SELECT policy FROM stock_policy_override WHERE merchant_id = ?", String.class, merchantId);
        String value = overrides.isEmpty() ? tenantStockPolicy : overrides.get(0);
        return StockPolicy.valueOf(value);
    }

    public void record(StockAdjustment adjustment) {
        entityManager.persist(adjustment);
    }

    public void hold(StockReservation reservation) {
        entityManager.persist(reservation);
    }
}
