package com.saathi.shared.security;

import com.saathi.shared.config.SaathiProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The order's contact number at rest (§11.4, §10.5.5's {@code contacts/reveal}).
 *
 * <p>An order has to keep the number the customer placed it with: the rider needs it, and a
 * support ticket reopened a month later still needs it. Storing the E.164 value as text would make
 * every database backup, replica and support export a contact list, so the column holds an
 * AES-256-GCM ciphertext instead — the same "the data is not the data" rule §12.8 applies to OTP
 * targets, with the difference that this one must be reversible when a permitted reader asks.
 *
 * <p>The key is an HKDF subkey of the single configured secret ({@link KeyDerivation#CONTACT_PHONE}),
 * so a leaked session key does not decrypt orders and a leaked order key does not mint sessions.
 * The IV is random per write and travels in front of the tag; version {@code v1} prefixes the blob
 * so a future rotation can recognize what it is looking at rather than guess.
 */
@Component
public class ContactCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public ContactCipher(SaathiProperties properties) {
        this.key = new SecretKeySpec(
            KeyDerivation.subkey(properties.sessionSecret(), KeyDerivation.CONTACT_PHONE), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("a contact number is required");
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + sealed.length);
            buffer.put(iv).put(sealed);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to seal the contact number", e);
        }
    }

    /** Only for a permitted reveal (§10.5.5's {@code contacts/reveal}, which is audited). */
    public String decrypt(String blob) {
        if (blob == null || !blob.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a v1 contact ciphertext");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(blob.substring(PREFIX.length()));
        if (decoded.length <= IV_BYTES) {
            throw new IllegalArgumentException("contact ciphertext is truncated");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, decoded, 0, IV_BYTES));
            byte[] opened = cipher.doFinal(decoded, IV_BYTES, decoded.length - IV_BYTES);
            return new String(opened, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to open the contact number", e);
        }
    }
}
