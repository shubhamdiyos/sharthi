package com.saathi.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Six digits that were sent to somebody (§12.8).
 *
 * <p>Nothing here is the phone or the code. The phone arrives as an HMAC under the
 * {@code otp-target} subkey — a plain SHA-256 of an Indian mobile is brute-forceable in minutes,
 * and this column is both the lookup key and the throttle key, so it has to be deterministic and
 * still not be the number. The code arrives as an HMAC under {@code otp-code}, bound to this row's
 * id so that a leaked table is not a list of six-digit values to try against every other row.
 *
 * <p>Expiry and attempt limits are evaluated on read, never by a job: {@code expires_at} is
 * compared against the injected {@link java.time.Clock}, which is the only reason §12.8's
 * five-minute window is testable at all.
 */
@Entity
@Table(name = "otp_challenge")
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class OtpChallenge {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 26)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private OtpPurpose purpose;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "target_hash", length = 64, nullable = false)
    private String targetHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private OtpState state = OtpState.OPEN;

    /** Bounded 0-5 by {@code otp_attempts_bounded}: the column cannot count past the limit. */
    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** The X-Request-Id that asked for the code: the join from a support call to this row. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 26, nullable = false)
    private String requestId;

    public boolean isOpenAt(Instant at) {
        return state == OtpState.OPEN && at.isBefore(expiresAt);
    }
}
