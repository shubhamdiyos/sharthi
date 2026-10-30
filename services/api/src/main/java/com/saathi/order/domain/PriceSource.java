package com.saathi.order.domain;

/** Where the charged line price came from: the list price, the scale, or a correction (§7.5). */
public enum PriceSource {
    LIST,
    WEIGHED,
    CORRECTED
}
