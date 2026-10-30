package com.saathi.catalog.repository;

import com.saathi.catalog.domain.Product;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Product rows. The two write paths are §7.3's conditional reservation and the merchant's edits. */
public interface ProductRepository extends JpaRepository<Product, String> {

    Optional<Product> findByCode(String code);

    Optional<Product> findByTenantIdAndCode(String tenantId, String code);

    Optional<Product> findByIdAndTenantId(String id, String tenantId);

    Optional<Product> findByMerchantIdAndCode(String merchantId, String code);

    List<Product> findByIdIn(List<String> ids);

    List<Product> findByIdInAndTenantId(List<String> ids, String tenantId);

    long countByMerchantIdAndActiveTrue(String merchantId);

    /** The storefront listing of one shop, keyset-paged on {@code (name, id)}. */
    @Query("""
        select p from Product p
         where p.merchantId = :merchantId
           and p.active = true
           and p.available = true
           and (:categoryId = '' or p.categoryId = :categoryId)
           and (:q = '' or lower(p.name) like :qLike)
           and (:afterName = '' or p.name > :afterName
                or (p.name = :afterName and p.id > :afterId))
         order by p.name asc, p.id asc
        """)
    List<Product> shopListing(
        @Param("merchantId") String merchantId,
        @Param("categoryId") String categoryId,
        @Param("q") String q,
        @Param("qLike") String qLike,
        @Param("afterName") String afterName,
        @Param("afterId") String afterId,
        Pageable pageable);

    /** The merchant's own catalogue, including unavailable and inactive rows (§10.5.6). */
    @Query("""
        select p from Product p
         where p.merchantId = :merchantId
           and (:state = '' or (:state = 'ACTIVE' and p.active and p.available)
                or (:state = 'UNAVAILABLE' and p.active and p.available = false)
                or (:state = 'INACTIVE' and p.active = false))
           and (:q = '' or lower(p.name) like :qLike)
           and (:afterName = '' or p.name > :afterName
                or (p.name = :afterName and p.id > :afterId))
         order by p.name asc, p.id asc
        """)
    List<Product> merchantListing(
        @Param("merchantId") String merchantId,
        @Param("state") String state,
        @Param("q") String q,
        @Param("qLike") String qLike,
        @Param("afterName") String afterName,
        @Param("afterId") String afterId,
        Pageable pageable);

    /** The merchant's low/out shelves (§10.5.6): counted products only, §7.1's projection. */
    @Query("""
        select p from Product p
         where p.merchantId = :merchantId
           and p.active = true
           and p.trackStock = true
           and (:state = 'out' and p.stockQty - p.reservedQty <= 0
                or :state = 'low' and p.stockQty - p.reservedQty > 0 and p.stockQty <= 10)
         order by p.stockQty asc, p.name asc
        """)
    List<Product> stockShelves(
        @Param("merchantId") String merchantId,
        @Param("state") String state,
        Pageable pageable);

    /** Storefront search: products of the shops that are visible in this city. */
    @Query("""
        select p from Product p
         where p.tenantId = :tenantId
           and p.merchantId in :merchantIds
           and p.active = true
           and p.available = true
           and lower(p.name) like :qLike
           and (:afterName = '' or p.name > :afterName
                or (p.name = :afterName and p.id > :afterId))
         order by p.name asc, p.id asc
        """)
    List<Product> search(
        @Param("tenantId") String tenantId,
        @Param("merchantIds") List<String> merchantIds,
        @Param("qLike") String qLike,
        @Param("afterName") String afterName,
        @Param("afterId") String afterId,
        Pageable pageable);

}
