package com.saathi.quote.service;

import com.saathi.quote.domain.PriceQuote;
import com.saathi.quote.domain.PriceQuoteLine;
import java.util.List;

/** A quote and its lines, as the service and the views pass them around. */
public record QuoteSnapshot(PriceQuote quote, List<PriceQuoteLine> lines) {
}
