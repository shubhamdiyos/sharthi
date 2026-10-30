package com.saathi.merchant.repository;

import com.saathi.merchant.domain.Merchant;
import com.saathi.merchant.domain.MerchantState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Merchant rows. Every finder names a tenant: §4.3-5 answers another city's shop with 404. */
public interface MerchantRepository extends JpaRepository<Merchant, String> {

    Optional<Merchant> findByTenantIdAndCode(String tenantId, String code);

    Optional<Merchant> findByIdAndTenantId(String id, String tenantId);

    List<Merchant> findByTenantIdAndStateOrderByTradeNameAsc(String tenantId, MerchantState state);

    long countByTenantIdAndStateAndHiddenReasonIsNull(String tenantId, MerchantState state);

    /**
     * The storefront list (§10.5.3): visible shops of one city, optionally narrowed to a ward, a
     * category or a name fragment, keyset-paged on {@code (tradeName, id)} so a shop renamed between
     * two pages cannot make a row repeat or vanish (§10.3).
     */
    @Query("""
        select m from Merchant m
         where m.tenantId = :tenantId
           and m.state = :state
           and m.hiddenReason is null
           and (:wardId <> '' and m.wardId = :wardId or :wardId = '')
           and (:categoryId <> '' and m.categoryId = :categoryId or :categoryId = '')
           and (:q = '' or lower(m.tradeName) like :qLike)
           and (:afterName = '' or m.tradeName > :afterName
                or (m.tradeName = :afterName and m.id > :afterId))
         order by m.tradeName asc, m.id asc
        """)
    List<Merchant> storefront(
        @Param("tenantId") String tenantId,
        @Param("state") MerchantState state,
        @Param("wardId") String wardId,
        @Param("categoryId") String categoryId,
        @Param("q") String q,
        @Param("qLike") String qLike,
        @Param("afterName") String afterName,
        @Param("afterId") String afterId,
        Pageable pageable);

    /** The operator's directory (§10.5.8): includes hidden shops and every non-archived state. */
    @Query("""
        select m from Merchant m
         where m.tenantId = :tenantId
           and (:state is null or m.state = :state)
           and (:wardId <> '' and m.wardId = :wardId or :wardId = '')
           and (:categoryId <> '' and m.categoryId = :categoryId or :categoryId = '')
           and (:q = '' or lower(m.tradeName) like :qLike)
           and (:afterName = '' or m.tradeName > :afterName
                or (m.tradeName = :afterName and m.id > :afterId))
         order by m.tradeName asc, m.id asc
        """)
    List<Merchant> directory(
        @Param("tenantId") String tenantId,
        @Param("state") MerchantState state,
        @Param("wardId") String wardId,
        @Param("categoryId") String categoryId,
        @Param("q") String q,
        @Param("qLike") String qLike,
        @Param("afterName") String afterName,
        @Param("afterId") String afterId,
        Pageable pageable);
}
