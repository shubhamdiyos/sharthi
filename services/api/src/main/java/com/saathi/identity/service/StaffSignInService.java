package com.saathi.identity.service;

import com.saathi.identity.domain.OtpChallenge;
import com.saathi.identity.domain.OtpPurpose;
import com.saathi.identity.domain.UserAccount;
import com.saathi.identity.domain.UserRoleGrant;
import com.saathi.identity.otp.OtpService;
import com.saathi.identity.repository.UserRoleGrantRepository;
import com.saathi.identity.session.SessionService;
import com.saathi.identity.session.SignedIn;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.tenant.PrincipalKind;
import com.saathi.tenancy.service.FeatureFlagReader;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The staff path, which is two factors in one endpoint (§12.1, §10.5.1).
 *
 * <p>{@code POST /auth/login} is called twice: with an email and a password it answers
 * {@code MFA_REQUIRED} and the id of the challenge it just sent, and with the same email and password
 * plus {@code otp_challenge_id} and {@code otp_code} it issues the session. The alternative — a
 * pending-authentication state that the second call redeems — is a third kind of credential with its
 * own expiry, its own revocation and its own table, and §12.4's promise that the session row decides
 * access is easier to keep when nothing else does. Presenting the password twice is what a client
 * that has already typed it can do without new state.
 *
 * <p>Nothing here is transactional, for the reason recorded on {@link SignInService}: a refusal must
 * not roll back the counter that produced it.
 */
@Service
@RequiredArgsConstructor
public class StaffSignInService {

    private final OtpService otp;
    private final PasswordVerifier passwords;
    private final UserRoleGrantRepository grants;
    private final FeatureFlagReader featureFlags;
    private final SessionService sessions;

    /** Either a session or the challenge the client must now answer — never both, never neither. */
    public record StaffSignIn(SignedIn session, SignInService.IssuedChallenge challenge) {
        public boolean signedIn() {
            return session != null;
        }
    }

    public StaffSignIn signIn(String rawEmail, String password, String challengeId, String code,
                              RequestTags.Device device, String requestId) {
        PasswordVerification check = passwords.verify(rawEmail, password);
        check.throwIfFailed();
        UserAccount account = check.account();

        if (code == null || challengeId == null) {
            if (secondFactorIsOptional(account)) {
                return new StaffSignIn(sessions.issueForAccount(account, device), null);
            }
            return new StaffSignIn(null, sendSecondFactor(account, requestId));
        }
        // The phone is read from the row the password just unlocked, never from the request: a
        // staffer's second factor is bound to the number on file, and accepting one from the caller
        // would let either factor be redeemed for a different person.
        otp.verify(OtpPurpose.LOGIN, challengeId, account.getPhone(), code).throwIfFailed();
        return new StaffSignIn(sessions.issueForAccount(account, device), null);
    }

    /**
     * §12.7's outage fallback, and the one place it is allowed to exist: a tenant holding
     * {@code mfa_optional} may sign in on the password alone while the OTP provider is down. A super
     * admin is refused in code rather than by a missing row, because a platform-wide flag would be a
     * back door into every city at once.
     */
    private boolean secondFactorIsOptional(UserAccount account) {
        if (account.getKind() == PrincipalKind.SUPER_ADMIN) {
            return false;
        }
        String tenantId = grants.findByUserAccountIdAndRevokedAtIsNull(account.getId()).stream()
            .map(UserRoleGrant::getTenantId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        return featureFlags.isEnabled(FeatureFlagReader.MFA_OPTIONAL, tenantId);
    }

    private SignInService.IssuedChallenge sendSecondFactor(UserAccount account, String requestId) {
        OtpChallenge challenge = otp.request(OtpPurpose.LOGIN, account.getPhone(), requestId);
        return new SignInService.IssuedChallenge(challenge.getId(), challenge.getExpiresAt());
    }
}
