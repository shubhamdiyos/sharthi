package com.saathi.identity.otp;

/**
 * The seam D5/OD-8 left open: the platform generates and verifies a code, and <em>somebody
 * else</em> delivers it.
 *
 * <p>One method, and it is allowed to fail. A provider that swallows an outage turns every login
 * into "your code is invalid", which §12.7's R-09 row identifies as the worst outcome an OTP
 * provider can have — so the exception is the honest answer, and the caller decides whether the
 * customer sees {@code INTERNAL_ERROR} or a retry.
 */
public interface OtpProvider {

    void send(OtpMessage message);
}
