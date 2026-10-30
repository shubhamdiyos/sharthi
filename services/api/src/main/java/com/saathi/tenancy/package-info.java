/**
 * Saathi module: {@code tenancy}.
 *
 * <p>Owns: tenants, service wards, tenant status, tenant_category, feature flags.
 *
 * <p>Boundary rule (System Design §3): another module may read this one only through its
 * service interface. Cross-module table access is a review-blocking defect, enforced by
 * {@code ModuleBoundaryTest} rather than by memory.
 */
package com.saathi.tenancy;
