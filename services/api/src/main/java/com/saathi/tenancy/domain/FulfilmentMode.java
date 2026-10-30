package com.saathi.tenancy.domain;

/**
 * Who carries an order to the door (OD-2). Two values because the pilot has exactly two: a
 * platform rider, or the shop's own person.
 *
 * <p>It is a {@code fulfilment_mode} on {@code ward_fee_rule} rather than a field on a delivery
 * job, because the fee is decided per ward and mode before any rider exists — on a
 * merchant-delivered order the platform pays no rider at all, and the drawn screens keep the
 * ₹25 with the shop.
 */
public enum FulfilmentMode {
    PLATFORM,
    MERCHANT
}
