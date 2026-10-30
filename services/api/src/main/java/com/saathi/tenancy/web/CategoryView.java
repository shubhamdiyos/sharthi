package com.saathi.tenancy.web;

import com.saathi.tenancy.service.ServiceabilityService.ActivatedCategory;

/**
 * One taxonomy node as a city presents it (§10.5.3's {@code CategoryView}).
 *
 * <p>{@code slug} is the handle a client sends back in {@code GET /shops?category=}; the id never
 * appears (§4.3-6). {@code requires_licence} comes from the global node and a city cannot rename it
 * away, which is the point of splitting the two tables: a shop's licence requirement is a platform
 * rule about a kind of goods, not a city's preference (§5.3, PRD §8.3).
 */
public record CategoryView(String slug, String name, boolean requiresLicence) {

    public static CategoryView of(ActivatedCategory activated) {
        return new CategoryView(activated.slug(), activated.displayName(), activated.requiresLicence());
    }
}
