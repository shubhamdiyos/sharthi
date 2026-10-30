package com.saathi.order.domain;

/**
 * Who physically holds the customer's money (§9.2). It follows the fulfilment snapshot: a
 * platform-delivered COD order is rider cash, a shop-delivered one is merchant cash.
 */
public enum CollectionChannel {
    RIDER_CASH,
    MERCHANT_CASH,
    OPERATOR_CASH,
    OFFLINE_RECORD
}
