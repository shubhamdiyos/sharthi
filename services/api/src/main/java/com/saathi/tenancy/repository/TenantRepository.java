package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.Tenant;
import com.saathi.tenancy.domain.TenantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenants are addressed by {@code code}, never by id (§4.3-6), and the public surface only ever
 * sees the {@code LIVE} ones — so {@code findByStatus} is the only list query the storefront needs
 * and it is deliberately not a method a caller can call without naming a status.
 */
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /**
     * A public {@code city_code} is typed by a person, so the read is case-insensitive: Siwan is one
     * city whether the customer writes {@code siwan}, {@code SIWAN} or {@code Siwan}, and a storefront
     * that answers two of the three with {@code OUTSIDE_SERVICE_AREA} is a storefront that looks
     * broken. The database's unique index is on {@code code} alone, which still allows two rows that
     * differ only by case; if that ever happens this returns two candidates and the lookup fails
     * loudly rather than serving whichever city the driver felt like picking.
     */
    Optional<Tenant> findByCodeIgnoreCase(String code);

    List<Tenant> findByStatusOrderByCodeAsc(TenantStatus status);
}
