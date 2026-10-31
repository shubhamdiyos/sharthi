package com.saathi.stock.service;

import com.saathi.catalog.service.StockBook;
import com.saathi.shared.id.Ulid;
import com.saathi.stock.domain.ReservationState;
import com.saathi.stock.domain.StockReservation;
import com.saathi.stock.repository.StockLedger;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * §7.2's reservations for the order lifecycle.
 *
 * <p>A hold is two rows that must agree: the {@code product.reserved_qty} increment (through
 * {@link StockBook}, the only writer of that column) and the {@code stock_reservation} row that
 * says which order line the quantity belongs to. Writing them in one transaction is what lets a
 * cancellation release exactly the quantity it held, months later, without guessing.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StockBook book;
    private final StockLedger ledger;
    private final Clock clock;

    /** §7.2: the city's policy unless this shop overrides it. */
    @Transactional(readOnly = true)
    public com.saathi.stock.domain.StockPolicy effectivePolicy(String tenantPolicy,
                                                               String merchantId) {
        return ledger.effectivePolicy(tenantPolicy, merchantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(String tenantId, String orderId, String orderLineId, String productId,
                     BigDecimal qty) {
        book.reserveOrFail(tenantId, productId, qty);
        ledger.hold(StockReservation.builder()
            .id(Ulid.next(clock))
            .tenantId(tenantId)
            .productId(productId)
            .orderId(orderId)
            .orderLineId(orderLineId)
            .qtyReserved(qty)
            .state(ReservationState.HELD)
            .createdAt(clock.instant())
            .build());
    }

    /** T4/T5/T9/T10/T20: the goods are no longer promised to an order that ended. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAll(String tenantId, String orderId) {
        List<StockReservation> held = ledger.openReservations(orderId);
        for (StockReservation reservation : held) {
            book.release(tenantId, reservation.getProductId(), reservation.getQtyReserved());
            reservation.setState(ReservationState.RELEASED);
            reservation.setClosedAt(clock.instant());
        }
    }

    /**
     * §7.2's weighing true-up — the only place a reservation changes size.
     *
     * <p>The held quantity is read from the row rather than passed in: a caller that recomputed the
     * original hold would have to reproduce the tolerance rounding, and a reservation that drifted
     * from {@code product.reserved_qty} by a rounding step is an inconsistency no order can be
     * cancelled against later. Keyed by line, not product, because §7.2 reserves per order line.
     *
     * <p>No held row is not an error. A city on {@code FLAG_ONLY} holds nothing and its shops still
     * weigh, so a stock policy must not decide whether T7 is available; the tolerance check that
     * guards this call compares quantities, not holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void trueUp(String tenantId, String orderId, String orderLineId, BigDecimal to) {
        ledger.openReservations(orderId).stream()
            .filter(reservation -> reservation.getOrderLineId().equals(orderLineId))
            .findFirst()
            .ifPresent(held -> {
                book.trueUp(tenantId, held.getProductId(), held.getQtyReserved(), to);
                held.setQtyReserved(to);
            });
    }
}
