package com.saathi.it;

import com.saathi.identity.otp.OtpMessage;
import com.saathi.identity.otp.OtpProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The seam §12.8 describes as "somebody else delivers it", filled by the test so a test can read
 * what was delivered.
 *
 * <p>The alternative is worse at every option. Grepping the api's own log for the six digits would
 * make an assertion depend on a logging format; recomputing the code from {@code target_hash} is
 * impossible by design and brute-forcing 10^6 HMACs to find it would be a test that looks like an
 * attack; and handing the tests a "dev code" that always verifies — the usual short cut — would put
 * a back door in the class every login goes through, which is the one thing §12.8's R-09 row says
 * the platform may not have.
 *
 * <p>So this is a bean in test sources, marked {@code @Profile("test")} like
 * {@link ProbeController} is, and production still gets exactly one {@link OtpProvider}: the
 * configured one. Nothing about the code's life changes — it is still generated in
 * {@link com.saathi.identity.otp.OtpService}, hashed before it is written, and never stored — the
 * only difference is that the one process that is allowed to know it is the one running the tests.
 */
@Primary
@Component
@Profile("test")
class RecordingOtpProvider implements OtpProvider {

    private final Map<String, String> lastCodeByPhone = new ConcurrentHashMap<>();

    @Override
    public void send(OtpMessage message) {
        lastCodeByPhone.put(message.to(), message.code());
    }

    /** The digits this process was asked to deliver to that number last, or null if never. */
    String codeFor(String e164) {
        String code = lastCodeByPhone.get(e164);
        if (code == null) {
            throw new IllegalStateException("no OTP was sent to " + e164 + " by this context; "
                + "request a challenge before trying to redeem one");
        }
        return code;
    }
}
