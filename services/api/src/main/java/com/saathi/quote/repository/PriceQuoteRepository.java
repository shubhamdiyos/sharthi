package com.saathi.quote.repository;

import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.QuoteState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Quote rows. The bulk supersede is one statement so the partial unique index never sees two ACTIVE rows. */
public interface PriceQuoteRepository extends JpaRepository<PriceQuote, String> {

    Optional<PriceQuote> findByIdAndTenantId(String id, String tenantId);

    Optional<PriceQuote> findFirstByCartIdAndStateOrderByIssuedAtDesc(String cartId, QuoteState state);

    Optional<PriceQuote> findFirstByOrderIdAndStateOrderByIssuedAtDesc(String orderId, QuoteState state);

    /**
     * {@code flushAutomatically} is required: the new ACTIVE row is inserted after this update, and
     * {@code price_quote_active_cart} is a partial unique index, so an unflushed update would make
     * the insert collide with the row it was meant to replace.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update PriceQuote q set q.state = :to
         where q.cartId = :cartId and q.state = :from
        """)
    int supersedeActiveCart(@Param("cartId") String cartId,
                            @Param("from") QuoteState from, @Param("to") QuoteState to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update PriceQuote q set q.state = :to
         where q.orderId = :orderId and q.state = :from
        """)
    int supersedeActiveOrder(@Param("orderId") String orderId,
                             @Param("from") QuoteState from, @Param("to") QuoteState to);
}
