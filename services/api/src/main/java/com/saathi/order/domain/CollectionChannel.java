package com.saathi.order.domain;

import com.saathi.tenancy.domain.FulfilmentMode;

/**
 * Who physically holds the customer's money (§9.2). It follows the fulfilment snapshot: a
 * platform-delivered COD order is rider cash, a shop-delivered one is merchant cash.
 */
public enum CollectionChannel {
    RIDER_CASH,
    MERCHANT_CASH,
    OPERATOR_CASH,
    OFFLINE_RECORD;

    /**
     * The channel a mode implies, for the two places that set it: the placement and §6.2's reselect.
     * A mode change that left the channel behind would hand a shop a rider's custody row (§9.4), so
     * the pair is one rule and not two call sites.
     */
    public static CollectionChannel forMode(FulfilmentMode mode) {
        return mode == FulfilmentMode.MERCHANT ? MERCHANT_CASH : RIDER_CASH;
    }
}
