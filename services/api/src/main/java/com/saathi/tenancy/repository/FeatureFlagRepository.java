package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.FeatureFlag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Flags are read as a pair of lists — the platform defaults and this tenant's overrides — and
 * merged in the service. One query with an {@code OR} would look cheaper but would need a
 * tie-break in SQL, and "which row won" is the question PRD §12's kill switch has to answer
 * audibly.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {

    List<FeatureFlag> findByTenantIdIsNullOrderByKeyAsc();

    List<FeatureFlag> findByTenantIdOrderByKeyAsc(String tenantId);
}
