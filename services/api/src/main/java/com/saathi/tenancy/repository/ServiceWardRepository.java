package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.ServiceWard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A ward is only ever looked up inside a tenant (§4.2). {@code findByWardNo} does not exist,
 * because "Ward 12" alone is two different places in the pilot's two live-and-draft cities, and a
 * repository method that invites the caller to guess which one is the shape of a tenancy bug.
 */
public interface ServiceWardRepository extends JpaRepository<ServiceWard, String> {

    Optional<ServiceWard> findByTenantIdAndWardNo(String tenantId, int wardNo);

    List<ServiceWard> findByTenantIdAndActiveTrueOrderBySortAscWardNoAsc(String tenantId);

    List<ServiceWard> findByTenantIdOrderBySortAscWardNoAsc(String tenantId);
}
