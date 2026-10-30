package com.saathi.shared.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code ip_hash = HMAC-SHA256(ip, daily_key)} (§13.4). The platform needs to correlate
 * abuse by source address without ever storing a customer's or a staff member's IP in the
 * clear, and a rotating key means a leaked table cannot be cross-referenced against logs
 * forever.
 */
public final class IpHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private IpHasher() {
    }

    public static String hash(String ip, String dailyKey, LocalDate day) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(dailyKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((day + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to hash an IP address", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
