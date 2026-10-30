package com.saathi.identity.service;

import com.saathi.identity.domain.AccountState;
import com.saathi.identity.domain.UserAccount;
import com.saathi.identity.repository.UserAccountRepository;
import com.saathi.identity.security.PasswordVault;
import com.saathi.shared.contact.EmailNormaliser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §12.8's password rules against one row.
 *
 * <p>Six consecutive failures lock the account for thirty minutes, and the lock expires by being
 * read rather than by a job: §6.5 removed timers from the product, so a locked row whose
 * {@code locked_until} has passed is unlocked by whoever presents the correct password next. That is
 * also why the column is never left set on an {@code ACTIVE} account — the pair
 * {@code state = LOCKED AND locked_until IS NULL} is refused by a CHECK, and a stale instant on an
 * active row would be a lock that reads as permanent.
 *
 * <p><b>Not implemented, and said out loud.</b> §12.8 also asks for ten password verifications per
 * account per fifteen minutes. That needs an attempt history; {@code user_account} has one counter
 * and {@code failed_auth_count BETWEEN 0 AND 20} is a lockout threshold, not a window. Recording the
 * window means a table (or a Redis string with a TTL), which is a schema decision for the founder, not
 * a line to invent here. The edge half of the same rule is the proxy's job and is not built either.
 */
@Service
@RequiredArgsConstructor
public class PasswordVerifier {

    /** §12.8: six consecutive failures. */
    static final int MAX_FAILURES = 6;
    /** §12.8: then a thirty-minute lock, and a 423 while it holds. */
    static final Duration LOCKOUT = Duration.ofMinutes(30);

    private final UserAccountRepository accounts;
    private final PasswordVault vault;
    private final Clock clock;

    @Transactional
    public PasswordVerification verify(String rawEmail, String rawPassword) {
        String email = EmailNormaliser.required(rawEmail, "email");
        UserAccount account = accounts.findByEmail(email).orElse(null);
        if (account == null || account.getPasswordHash() == null) {
            // A customer or rider row has no hash by CHECK, not by omission. Answering the same way
            // for an unknown address, a known address and a non-staff account is what makes
            // POST /auth/login unable to be used as a directory (§12.2).
            return new PasswordVerification(null, PasswordVerification.Failure.BAD_CREDENTIALS);
        }

        Instant now = clock.instant();
        if (account.getState() == AccountState.LOCKED) {
            if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
                return new PasswordVerification(account, PasswordVerification.Failure.LOCKED);
            }
            unlock(account);
        }

        if (!vault.matches(rawPassword, account.getPasswordHash())) {
            int failures = account.getFailedAuthCount() + 1;
            account.setFailedAuthCount(failures);
            if (failures >= MAX_FAILURES) {
                account.setState(AccountState.LOCKED);
                account.setLockedUntil(now.plus(LOCKOUT));
            }
            accounts.save(account);
            return new PasswordVerification(account, PasswordVerification.Failure.BAD_CREDENTIALS);
        }

        if (account.getFailedAuthCount() != 0) {
            account.setFailedAuthCount(0);
            accounts.save(account);
        }
        // Only after the password: §12.2 gives a deactivated account its own code to its owner, and
        // a wrong password must not be the thing that reveals the state.
        if (account.getState() == AccountState.DEACTIVATED) {
            return new PasswordVerification(account, PasswordVerification.Failure.DEACTIVATED);
        }
        return new PasswordVerification(account, PasswordVerification.Failure.NONE);
    }

    /** A lock that has run out clears its own instant, so no {@code ACTIVE} row carries one. */
    private void unlock(UserAccount account) {
        account.setState(AccountState.ACTIVE);
        account.setLockedUntil(null);
        account.setFailedAuthCount(0);
        accounts.save(account);
    }
}
