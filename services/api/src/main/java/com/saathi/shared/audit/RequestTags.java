package com.saathi.shared.audit;

import com.saathi.shared.config.SaathiProperties;
import com.saathi.shared.security.KeyDerivation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The two {@code char(64)} fingerprint columns, written the same way everywhere.
 *
 * <p>§13.4's rule is that the platform can correlate abuse by source without holding the source
 * address. A plain hash would not: ten million Indian mobile subscribers fit in a GPU afternoon, so
 * the key matters more than the algorithm. The key is an HKDF subkey of the configured audit secret
 * named with the day, which is what makes a leaked table a pile of values that cannot be matched
 * against anything the holder has — and why re-deriving yesterday's key is a computation, not a
 * lookup.
 *
 * <p>The user agent goes through the same function: §12.5 wants "this device" to be recognisable to
 * its owner without the session row carrying a string that can be sold as a tracking profile.
 *
 * <p>{@code getRemoteAddr()} and not {@code X-Forwarded-For}: a header the client controls cannot be
 * evidence about a client. A deployment behind a proxy therefore hashes the proxy, and turning on
 * {@code server.forward-headers-strategy} is the reviewed way to change that — which is a decision
 * about what the proxy is trusted to say, not a line of code.
 */
@Component
@RequiredArgsConstructor
public class RequestTags {

    /** Long enough to recognise a browser, short enough not to store an entire UA string. */
    private static final int DEVICE_LABEL_LIMIT = 60;

    private final SaathiProperties properties;
    private final Clock clock;

    public record Device(String ipHash, String userAgentHash, String deviceLabel) {
    }

    public Device of(HttpServletRequest request) {
        LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        String dailyKey = dailyKey(day);
        String userAgent = request.getHeader("User-Agent");
        return new Device(
            IpHasher.hash(request.getRemoteAddr(), dailyKey, day),
            IpHasher.hash(userAgent, dailyKey, day),
            label(userAgent));
    }

    private String dailyKey(LocalDate day) {
        return HexFormat.of().formatHex(KeyDerivation.subkey(properties.auditIpKey(), "ip-daily|" + day));
    }

    private static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String trimmed = userAgent.trim();
        return trimmed.length() <= DEVICE_LABEL_LIMIT ? trimmed : trimmed.substring(0, DEVICE_LABEL_LIMIT);
    }
}
