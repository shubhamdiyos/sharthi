package com.saathi.identity.repository;

import com.saathi.identity.domain.UserSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    /**
     * The principal's own device list (§12.5), most recently used first.
     *
     * <p>Filtered on {@code revoked_at} and not on the two expiry columns: a session that went
     * stale on its own is still a device the customer remembers, and "revoked" versus "expired"
     * are different answers §12.5 promises the principal can see — so the rows load and the
     * service reads the timestamps to tell them apart.
     */
    List<UserSession> findByUserAccountIdAndRevokedAtIsNullOrderByLastSeenAtDesc(String userAccountId);
}
