package com.saathi.quote.domain;

/** §8.1's three reasons a quote exists. Day 1C issues {@code CART} only. */
public enum QuoteScope {
    CART,
    WEIGHT,
    FULFILMENT_CHANGE
}
