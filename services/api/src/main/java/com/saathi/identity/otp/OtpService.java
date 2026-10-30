package com.saathi.identity.otp;

import com.saathi.identity.domain.OtpChallenge;
import com.saathi.identity.domain.OtpPurpose;
import com.saathi.identity.domain.OtpState;
import com.saathi.identity.repository.OtpChallengeRepository;
import com.saathi.identity.security.IdentityKeys;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.id.Ulid;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything §12.8 says about an OTP, in one class: six digits, one challenge open per phone per
 * purpose, five minutes, five attempts, five requests an hour, hashed at rest, never in an error
 * body.
 *
 * <p>The code is a local variable. It is generated here, handed to the {@link OtpProvider} once,
 * and reduced to an HMAC before anything is written — so there is no path from this class to the
 * database that carries it, and no query that could return it.
 *
 * <p>Verification needs the phone as well as the challenge id, because {@code target_hash} is
 * one-way and the challenge row is the only place the relationship between a code and a phone
 * exists. The storefront already has the number on screen, so asking for it again costs the client
 * nothing; recomputing the hash and comparing it is what stops a challenge issued for one phone
 * being redeemed for another.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    /** §12.8: five-minute validity, evaluated on read. */
    static final Duration VALIDITY = Duration.ofMinutes(5);
    /** §12.8: at most five verify attempts per challenge. */
    static final int MAX_ATTEMPTS = 5;
    /** §12.8: five OTPs per phone per hour, counted from the rows themselves. */
    static final int MAX_PER_HOUR = 5;
    private static final Duration THROTTLE_WINDOW = Duration.ofHours(1);
    private static final int CODE_SPACE = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challenges;
    private final IdentityKeys keys;
    private final OtpProvider provider;
    private final Clock clock;

    /**
     * Issues a challenge and hands the code to the provider inside one transaction: a challenge
     * whose message failed to send would be a five-minute window in which a customer waits for an
     * SMS that is never coming, so the whole request is rolled back instead.
     */
    @Transactional
    public OtpChallenge request(OtpPurpose purpose, String e164, String requestId) {
        Instant now = clock.instant();
        String targetHash = keys.otpTargetHash(e164);

        if (challenges.countByTargetHashAndCreatedAtAfter(targetHash, now.minus(THROTTLE_WINDOW)) >= MAX_PER_HOUR) {
            throw new ApiException(ErrorCode.OTP_THROTTLED, "Too many codes requested for this number. Try again later.");
        }

        // V2's partial unique index allows one OPEN row per phone and purpose, so the previous
        // code is retired here rather than at the next request: "the newest code is the one that
        // works" is the behaviour a customer expects, and the old row keeps its history for the
        // hourly count.
        //
        // The flush is not decoration. Hibernate orders a flush as inserts-then-updates, so
        // leaving the retirement pending puts the new OPEN row in the index while the old one is
        // still OPEN too — a duplicate-key error on the request that is the most ordinary thing a
        // waiting customer does, which is asking for a code a second time.
        challenges.findFirstByTargetHashAndPurposeAndStateOrderByCreatedAtDesc(targetHash, purpose, OtpState.OPEN)
            .ifPresent(stale -> {
                stale.setState(OtpState.EXPIRED);
                challenges.saveAndFlush(stale);
            });

        String code = sixDigits();
        OtpChallenge challenge = OtpChallenge.builder()
            .id(Ulid.next(clock))
            .purpose(purpose)
            .targetHash(targetHash)
            .state(OtpState.OPEN)
            .attempts(0)
            .createdAt(now)
            .expiresAt(now.plus(VALIDITY))
            .requestId(requestId)
            .build();
        challenge.setCodeHash(keys.otpCodeHash(challenge.getId(), code));
        challenges.save(challenge);

        provider.send(new OtpMessage(e164, code, challenge.getExpiresAt(), purpose));
        log.info("OTP challenge issued for purpose {} request {}", purpose, requestId);
        return challenge;
    }

    /**
     * Redeems a code, or says why it cannot be redeemed. Nothing here throws: {@link OtpFailure}
     * records why a refusal has to survive the transaction that counted the attempt.
     */
    @Transactional
    public OtpFailure verify(OtpPurpose purpose, String challengeId, String e164, String code) {
        Instant now = clock.instant();
        OtpChallenge challenge = challenges.findById(challengeId).orElse(null);
        if (challenge == null) {
            // An unknown id is indistinguishable from a wrong code on purpose: "no such challenge"
            // would tell an attacker that ids are enumerable, and the answer they need is the one
            // the customer with a mistyped code gets anyway.
            return OtpFailure.UNKNOWN_CODE;
        }
        if (!challenge.getTargetHash().equals(keys.otpTargetHash(e164)) || challenge.getPurpose() != purpose) {
            return OtpFailure.WRONG_TARGET;
        }
        if (challenge.getState() == OtpState.CONSUMED) {
            return OtpFailure.ALREADY_CONSUMED;
        }
        if (challenge.getState() == OtpState.EXPIRED || !now.isBefore(challenge.getExpiresAt())) {
            if (challenge.getState() == OtpState.OPEN) {
                challenge.setState(OtpState.EXPIRED);
                challenges.save(challenge);
            }
            return OtpFailure.EXPIRED;
        }
        if (challenge.getState() == OtpState.FAILED || challenge.getAttempts() >= MAX_ATTEMPTS) {
            // No OTP_THROTTLED here: that code means "ask for another code later", and this
            // customer already has an open challenge they are out of attempts on.
            return OtpFailure.ATTEMPTS_EXHAUSTED;
        }

        if (!keys.otpCodeMatches(challenge.getId(), code, challenge.getCodeHash())) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= MAX_ATTEMPTS) {
                challenge.setState(OtpState.FAILED);
            }
            challenges.save(challenge);
            return OtpFailure.WRONG_CODE;
        }

        challenge.setState(OtpState.CONSUMED);
        challenge.setConsumedAt(now);
        challenges.save(challenge);
        return OtpFailure.NONE;
    }


    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(CODE_SPACE));
    }
}
