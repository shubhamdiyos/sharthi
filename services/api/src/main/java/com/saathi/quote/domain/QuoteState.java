package com.saathi.quote.domain;

/**
 * §8.1's quote lifecycle. {@code EXPIRED_BY_RULE} is written lazily on read (§6.5 forbids a
 * timer); {@code SUPERSEDED} is written by the module that replaces the quote.
 */
public enum QuoteState {
    ACTIVE,
    ACCEPTED,
    SUPERSEDED,
    EXPIRED_BY_RULE
}
