package com.saathi.tenancy.web;

import com.saathi.tenancy.domain.Tenant;

/**
 * §10.5.2's {@code CitySummary}: the three facts that let a customer name a city, and nothing else.
 *
 * <p>No {@code contact_phone}, no cash caps, no commission — a city's phone number and its money
 * rules are operator data, and a public list is the one surface where "no PII" has to be a property
 * of the type rather than of someone remembering to leave a field out.
 */
public record CitySummary(String code, String name, String stateCode) {

    public static CitySummary of(Tenant tenant) {
        return new CitySummary(tenant.getCode(), tenant.getName(), tenant.getStateCode());
    }
}
