package com.saathi.tenancy.web;

import com.saathi.tenancy.domain.ServiceWard;

/**
 * A ward as the storefront sees it (§10.5.2).
 *
 * <p>{@code ward_no} and {@code name} are the whole public shape, and {@code ward_no} is the value a
 * client sends back: the pair {@code (city_code, ward_no)} is how a ward is addressed anywhere in
 * this API (§4.3-6). The internal id stays out of a public list because a picker that returns ids
 * teaches every future client to guess that ids are addressable, and §4.2's composite keys exist to
 * make that guess wrong.
 *
 * <p>{@code sort}, {@code note} and {@code launched_at} are operator data. {@code sort} is applied
 * server-side to the list order, which is where a display order belongs.
 */
public record WardSummary(int wardNo, String name) {

    public static WardSummary of(ServiceWard ward) {
        return new WardSummary(ward.getWardNo(), ward.getName());
    }
}
