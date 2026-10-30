/**
 * Saathi module: {@code payment}.
 *
 * <p>Owns: payment_record, and the method / channel / status triples of D2.
 *
 * <p>Boundary rule (System Design §3): another module may read this one only through its
 * service interface. Cross-module table access is a review-blocking defect, enforced by
 * {@code ModuleBoundaryTest} rather than by memory.
 */
package com.saathi.payment;
