package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.TenantCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which nodes a city has switched on. The activation is unique per {@code (tenant, category)} in
 * the database, so "is meat-fish on in Siwan" is a single lookup rather than a scan of the
 * tenant's rows.
 */
public interface TenantCategoryRepository extends JpaRepository<TenantCategory, String> {

    List<TenantCategory> findByTenantIdAndActiveTrue(String tenantId);

    Optional<TenantCategory> findByTenantIdAndCategoryId(String tenantId, String categoryId);
}
