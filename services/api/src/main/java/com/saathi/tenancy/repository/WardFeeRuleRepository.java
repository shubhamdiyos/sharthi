package com.saathi.tenancy.repository;

import com.saathi.tenancy.domain.WardFeeRule;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Rates are read "as of" a moment, never "the one that is in the table". Two rows per ward-mode is
 * legal — the old rate and its replacement — so this returns them newest first and the caller takes
 * the first per mode, which is the same rule §9.7's recomputation uses on a two-year-old order.
 */
public interface WardFeeRuleRepository extends JpaRepository<WardFeeRule, String> {

    @Query("""
        select r from WardFeeRule r
         where r.tenantId = :tenantId and r.wardId = :wardId and r.active = true
           and r.validFrom <= :asOf
         order by r.validFrom desc, r.id desc
        """)
    List<WardFeeRule> findEffectiveAt(@Param("tenantId") String tenantId,
                                      @Param("wardId") String wardId,
                                      @Param("asOf") Instant asOf);
}
