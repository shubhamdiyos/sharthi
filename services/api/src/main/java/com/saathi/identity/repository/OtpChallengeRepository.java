package com.saathi.identity.repository;

import com.saathi.identity.domain.OtpChallenge;
import com.saathi.identity.domain.OtpPurpose;
import com.saathi.identity.domain.OtpState;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every method here takes a hash, not a phone number. That is the point: the service can count,
 * find and throttle challenges without any query in the application ever carrying a contact
 * address, which is what §10's "never log or look up by full phone number" means in code.
 */
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, String> {

    /** The one {@code OPEN} row for this phone and purpose, if V2's partial unique index allowed it. */
    Optional<OtpChallenge> findFirstByTargetHashAndPurposeAndStateOrderByCreatedAtDesc(
        String targetHash, OtpPurpose purpose, OtpState state);

    /** §12.8's "5 per phone per hour" counts rows, which is why history is kept instead of overwritten. */
    long countByTargetHashAndCreatedAtAfter(String targetHash, Instant since);
}
