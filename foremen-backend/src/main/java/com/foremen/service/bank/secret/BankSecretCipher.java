package com.foremen.service.bank.secret;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.exception.ForemenApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AES-256-GCM cipher for per-connection bank credentials (FOR-07-04, Requirement 2).
 *
 * <p>The key comes from configuration ({@code foremen.bank-secrets.key}, env
 * {@code FOREMEN_BANK_SECRETS_KEY}) as base64 of exactly 32 bytes and is never read from the
 * database. A blank or malformed key leaves the cipher <em>not configured</em>: the application
 * still starts, {@link #isConfigured()} returns {@code false} and any encrypt / decrypt call fails
 * with {@code 409 error.bank.secrets.notConfigured} (Requirement 2.5).
 *
 * <p>Each encryption uses a fresh random 12-byte IV and a 128-bit authentication tag; the payload is
 * the JSON serialization of the credential map. The result maps 1:1 onto the
 * {@code bank_connection_secrets} columns ({@code ciphertext}, {@code iv}, {@code key_version}).
 * Any modification of ciphertext, IV or tag makes {@link #decrypt(Encrypted)} throw
 * {@link BankSecretCipherException}.
 */
@Slf4j
@Component
public class BankSecretCipher {

    /** The only key version currently supported. */
    public static final short KEY_VERSION = 1;
    /** GCM IV length in bytes. */
    public static final int IV_LENGTH = 12;
    /** Error code for a missing / invalid encryption key. */
    public static final String NOT_CONFIGURED_CODE = "error.bank.secrets.notConfigured";

    private static final int KEY_LENGTH = 32;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public BankSecretCipher(@Value("${foremen.bank-secrets.key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    /** Whether a valid 32-byte key is configured. */
    public boolean isConfigured() {
        return key != null;
    }

    /**
     * Encrypts the credential map as JSON with a fresh random IV.
     *
     * @throws ForemenApiException 409 {@code error.bank.secrets.notConfigured} when no key is configured
     */
    public Encrypted encrypt(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        SecretKey k = requireKey();
        byte[] plaintext;
        try {
            plaintext = JSON.writeValueAsBytes(values);
        } catch (JsonProcessingException e) {
            throw new BankSecretCipherException("Cannot serialize bank secret", e);
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new Encrypted(cipher.doFinal(plaintext), iv, KEY_VERSION);
        } catch (GeneralSecurityException e) {
            throw new BankSecretCipherException("Bank secret encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Decrypts and authenticates a stored secret back to the credential map.
     *
     * @throws ForemenApiException 409 when no key is configured
     * @throws BankSecretCipherException on an unknown key version, a malformed IV or any tampering
     */
    public Map<String, String> decrypt(Encrypted encrypted) {
        Objects.requireNonNull(encrypted, "encrypted");
        SecretKey k = requireKey();
        if (encrypted.keyVersion() != KEY_VERSION) {
            throw new BankSecretCipherException("Unsupported bank secret key version " + encrypted.keyVersion());
        }
        if (encrypted.iv() == null || encrypted.iv().length != IV_LENGTH || encrypted.ciphertext() == null) {
            throw new BankSecretCipherException("Malformed bank secret");
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_LENGTH_BITS, encrypted.iv()));
            plaintext = cipher.doFinal(encrypted.ciphertext());
            return JSON.readValue(plaintext, MAP_TYPE);
        } catch (AEADBadTagException e) {
            throw new BankSecretCipherException("Bank secret authentication failed (tampered or wrong key)", e);
        } catch (GeneralSecurityException e) {
            throw new BankSecretCipherException("Bank secret decryption failed", e);
        } catch (java.io.IOException e) {
            throw new BankSecretCipherException("Cannot parse decrypted bank secret", e);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_CONFIGURED_CODE);
        }
        return key;
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.info("foremen.bank-secrets.key is not set; per-connection bank credentials are unavailable");
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            log.warn("foremen.bank-secrets.key is not valid base64; bank secret encryption is disabled");
            return null;
        }
        if (raw.length != KEY_LENGTH) {
            log.warn("foremen.bank-secrets.key must decode to {} bytes (got {}); bank secret encryption is disabled",
                    KEY_LENGTH, raw.length);
            Arrays.fill(raw, (byte) 0);
            return null;
        }
        SecretKey k = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        return k;
    }

    /**
     * An encrypted secret as stored in {@code bank_connection_secrets}: ciphertext (with the GCM tag
     * appended), 12-byte IV and key version.
     */
    public record Encrypted(byte[] ciphertext, byte[] iv, short keyVersion) {
        public Encrypted {
            ciphertext = ciphertext == null ? null : ciphertext.clone();
            iv = iv == null ? null : iv.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext == null ? null : ciphertext.clone();
        }

        @Override
        public byte[] iv() {
            return iv == null ? null : iv.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Encrypted other
                    && keyVersion == other.keyVersion
                    && Arrays.equals(ciphertext, other.ciphertext)
                    && Arrays.equals(iv, other.iv);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Arrays.hashCode(ciphertext) + Arrays.hashCode(iv)) + keyVersion;
        }

        @Override
        public String toString() {
            return "Encrypted[keyVersion=" + keyVersion + ", ciphertext=<" +
                    (ciphertext == null ? 0 : ciphertext.length) + " bytes>]";
        }
    }
}
