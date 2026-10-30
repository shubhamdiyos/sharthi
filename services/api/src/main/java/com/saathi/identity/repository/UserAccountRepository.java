package com.saathi.identity.repository;

import com.saathi.identity.domain.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Two lookups, because there are exactly two ways to be found: a customer by normalised phone, a
 * member of staff by lower-cased email (§12.1). Both are partial unique indexes in V2 — the
 * uniqueness is "where present", because a customer's NULL email must not collide with a rider's.
 *
 * <p>Neither method is tenant-scoped: {@code user_account} is a global table by decision (§4.1),
 * which is the one place §4.3's "every scoped query goes through a scoped repository" has a
 * documented exception.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByPhone(String phone);

    Optional<UserAccount> findByEmail(String email);
}
