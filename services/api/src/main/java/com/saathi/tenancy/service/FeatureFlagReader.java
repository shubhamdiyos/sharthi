package com.saathi.tenancy.service;

import com.saathi.tenancy.domain.FeatureFlag;
import com.saathi.tenancy.repository.FeatureFlagRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD §12's kill switches, resolved for one city (§5.2).
 *
 * <p>A tenant row wins over the platform default; a key with no row is off. The second half is what
 * makes the seed honest: V3 inserts only the switches that exist as decisions, so a capability that
 * has not been built has no row anywhere and cannot be turned on by a client that guesses its key.
 *
 * <p>Both lists are read rather than one query with an {@code OR} because "which row won" has to be
 * answerable in code a reviewer can read, and merging five rows in memory is not a cost worth a
 * tie-break in SQL.
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagReader {

    /** §12.7's outage fallback. Absent from the seed, which is what "off by default" means. */
    public static final String MFA_OPTIONAL = "mfa_optional";

    private final FeatureFlagRepository flags;

    @Transactional(readOnly = true)
    public Map<String, Boolean> effective(String tenantId) {
        Map<String, Boolean> merged = new TreeMap<>();
        collect(merged, flags.findByTenantIdIsNullOrderByKeyAsc());
        if (tenantId != null) {
            collect(merged, flags.findByTenantIdOrderByKeyAsc(tenantId));
        }
        return Map.copyOf(merged);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key, String tenantId) {
        return Boolean.TRUE.equals(effective(tenantId).get(key));
    }

    private static void collect(Map<String, Boolean> into, List<FeatureFlag> rows) {
        for (FeatureFlag flag : rows) {
            into.put(flag.getKey(), flag.isEnabled());
        }
    }
}
