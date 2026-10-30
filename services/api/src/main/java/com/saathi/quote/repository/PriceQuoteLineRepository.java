package com.saathi.quote.repository;

import com.saathi.quote.domain.PriceQuoteLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Quote lines are append-only; a re-quote is a new quote, not an edit of an old one. */
public interface PriceQuoteLineRepository extends JpaRepository<PriceQuoteLine, String> {

    List<PriceQuoteLine> findByQuoteIdOrderByIdAsc(String quoteId);
}
