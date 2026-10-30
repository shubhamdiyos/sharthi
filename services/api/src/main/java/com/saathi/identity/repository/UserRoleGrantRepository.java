package com.saathi.identity.repository;

import com.saathi.identity.domain.UserRoleGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * "Live" is {@code revoked_at is null}, and it is the only liveness rule: there is no separate
 * {@code active} column that could disagree with it (§12.5's revocation has to be unambiguous,
 * because a revoked grant has to stop working on the next request).
 */
public interface UserRoleGrantRepository extends JpaRepository<UserRoleGrant, String> {

    List<UserRoleGrant> findByUserAccountIdAndRevokedAtIsNull(String userAccountId);

    List<UserRoleGrant> findByUserAccountIdAndTenantIdAndRevokedAtIsNull(String userAccountId, String tenantId);
}
