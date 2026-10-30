/**
 * Saathi module: {@code jobs}.
 *
 * <p>Owns: the closed list of scheduled work (System Design 4.6); no job may act on business state.
 *
 * <p>Boundary rule (System Design §3): another module may read this one only through its
 * service interface. Cross-module table access is a review-blocking defect, enforced by
 * {@code ModuleBoundaryTest} rather than by memory.
 */
package com.saathi.jobs;
