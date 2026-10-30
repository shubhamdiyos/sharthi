package com.saathi.identity.otp;

import com.saathi.shared.config.SaathiProperties;
import com.saathi.shared.contact.PhoneNormaliser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The dev provider (D5, OD-8): it prints what an SMS gateway would have carried, and nothing more.
 *
 * <p><b>A recorded departure.</b> §12.8 says an OTP code never appears in a log. That rule is
 * about the platform's production logs, and it has an unstated consequence in dev: if the code is
 * nowhere, then the dev provider sends it nowhere, every login fails, and the pilot cannot be
 * clicked through by the people building it. Rather than discover that as a silent no-op — the
 * failure mode {@link SaathiProperties} was commented against — this class prints the digits and
 * refuses to exist outside {@code saathi.environment=dev}. The guard is in the constructor, so a
 * deployment that leaves {@code SAATHI_OTP_PROVIDER} at its default while setting
 * {@code SAATHI_ENVIRONMENT=prod} does not run a no-op login flow: the context fails at boot and
 * the container never becomes ready.
 *
 * <p>The phone is masked even here (§10). A developer needs the six digits to log in as themselves,
 * not a list of the pilot's customers.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "saathi", name = "otp-provider", havingValue = "logging")
public class LoggingOtpProvider implements OtpProvider {

    /** The constructor's only job is the guard: nothing here is needed after the check has passed. */
    public LoggingOtpProvider(SaathiProperties properties) {
        if (!properties.isDevelopment()) {
            throw new IllegalStateException("saathi.otp-provider=logging is a development-only provider; "
                + "select a real one (OD-8) before running in " + properties.environment());
        }
    }

    @Override
    public void send(OtpMessage message) {
        log.info("dev OTP to {} for {}: {} (expires {}) — dev-only provider, no message was sent",
            PhoneNormaliser.mask(message.to()), message.purpose(), message.code(), message.expiresAt());
    }
}
