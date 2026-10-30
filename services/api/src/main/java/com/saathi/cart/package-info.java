/**
 * Saathi module: {@code cart}.
 *
 * <p>Owns: cart, cart lines, the one-merchant rule, serviceability check.
 *
 * <p>Boundary rule (System Design §3): another module may read this one only through its
 * service interface. Cross-module table access is a review-blocking defect, enforced by
 * {@code ModuleBoundaryTest} rather than by memory.
 */
package com.saathi.cart;
