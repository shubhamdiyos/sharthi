package com.saathi.identity.service;

import com.saathi.identity.domain.AccountState;
import com.saathi.identity.domain.OtpChallenge;
import com.saathi.identity.domain.OtpPurpose;
import com.saathi.identity.domain.UserAccount;
import com.saathi.identity.otp.OtpService;
import com.saathi.identity.repository.UserAccountRepository;
import com.saathi.identity.session.SessionService;
import com.saathi.identity.session.SignedIn;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import com.saathi.shared.tenant.PrincipalKind;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The public way into a session (§10.5.1).
 *
 * <p>Nothing here is transactional. A sign-in is a sequence of commits that must each survive the
 * next: the OTP attempt counter commits before the account lookup, and the account commits before the
 * session row. Wrapping the flow in one transaction would roll a failed attempt back with the refusal
 * that reported it, which is §12.8's five-attempt cap quietly deleted — the reason
 * {@link OtpService#verify} returns a value instead of throwing.
 *
 * <p>A phone that has never signed in gets an account. §12.1 says a customer is created at
 * {@code POST /auth/otp/request} with {@code purpose=REGISTRATION}, and §10.5.1 says that endpoint
 * must answer identically whether or not the phone exists; the only place both can be true is verify,
 * where the code's owner has just proven they hold the number. So LOGIN and REGISTRATION behave the
 * same — D5 has no separate signup step — and the purpose is still stored on the challenge, which is
 * what lets a support conversation tell the two intents apart.
 */
@Service
@RequiredArgsConstructor
public class SignInService {

    /** The purposes a caller with no session may ask for. Every other purpose needs a principal. */
    private static final Set<OtpPurpose> PUBLIC_PURPOSES = Set.of(OtpPurpose.LOGIN, OtpPurpose.REGISTRATION);

    private final OtpService otp;
    private final UserAccountRepository accounts;
    private final SessionService sessions;
    private final Clock clock;

    /** What {@code POST /auth/otp/request} answers with, and what a pending staff login names. */
    public record IssuedChallenge(String challengeId, Instant expiresAt) {
    }

    /** §12.8's throttles apply here; the answer is the same for a phone with no account. */
    public IssuedChallenge requestChallenge(OtpPurpose purpose, String rawPhone, String requestId) {
        if (!PUBLIC_PURPOSES.contains(purpose)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "purpose",
                    "message", "Only LOGIN or REGISTRATION can be requested without a session."))));
        }
        String e164 = PhoneNormaliser.required(rawPhone, "phone");
        OtpChallenge challenge = otp.request(purpose, e164, requestId);
        return new IssuedChallenge(challenge.getId(), challenge.getExpiresAt());
    }

    /**
     * The storefront's one factor: the phone. A staff number is refused here, because §12.1 gives
     * staff two factors and redeeming the OTP alone would silently drop one of them.
     */
    public SignedIn signInWithOtp(OtpPurpose purpose, String rawPhone, String challengeId, String code,
                                  RequestTags.Device device) {
        String e164 = PhoneNormaliser.required(rawPhone, "phone");
        otp.verify(purpose, challengeId, e164, code).throwIfFailed();

        UserAccount account = accounts.findByPhone(e164).orElseGet(() -> register(e164));
        if (account.isStaff()) {
            // Accurate rather than anonymous. §12.2's no-enumeration rule is about a wrong
            // credential and this one was right, and a staffer told "that code is not right" when it
            // was will try again until the challenge is out of attempts.
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Sign in with your email and password.");
        }
        requireUsable(account);
        return sessions.issueForAccount(account, device);
    }

    private void requireUsable(UserAccount account) {
        if (account.getState() == AccountState.DEACTIVATED) {
            throw new ApiException(ErrorCode.ACCOUNT_DEACTIVATED, "This account is deactivated.");
        }
    }

    private UserAccount register(String e164) {
        UserAccount account = UserAccount.builder()
            .id(Ulid.next(clock))
            .kind(PrincipalKind.CUSTOMER)
            .phone(e164)
            .state(AccountState.ACTIVE)
            .build();
        accounts.save(account);
        return account;
    }
}
