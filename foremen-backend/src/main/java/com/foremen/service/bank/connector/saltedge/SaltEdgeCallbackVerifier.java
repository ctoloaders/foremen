package com.foremen.service.bank.connector.saltedge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies incoming Salt Edge callbacks (FOR-07-04, R10.2).
 *
 * <p>Salt Edge sends {@code Signature = base64(RSA-SHA256(saltEdgePrivateKey, "callback_url|post_body"))}
 * and the {@code Signature-key-version} header. {@link #verify} checks it with the public key for that
 * version. The documented keys are bundled on the classpath:
 * <ul>
 *   <li>{@code 5.0} — Partners API v1 / Account Information API v5
 *       ({@code saltedge/callback-public-key-5.0.pem});</li>
 *   <li>{@code 6.0} — API v6, used by CLIENT mode ({@code saltedge/callback-public-key-6.0.pem}).</li>
 * </ul>
 * A configured key ({@code foremen.bank-feed.saltedge.callback-public-key}) overrides the bundled keys
 * for every version.
 *
 * <p>The signed payload is built from the raw body bytes as received (no charset round-trip), so any
 * byte change in the body is detected. Pure and thread-safe; never throws for bad input — returns
 * {@code false} instead.
 */
public final class SaltEdgeCallbackVerifier {

    static final String BUNDLED_KEY_LOCATION = "saltedge/callback-public-key-%s.pem";
    static final String[] BUNDLED_KEY_VERSIONS = {"5.0", "6.0"};

    private final Map<String, PublicKey> keysByVersion;
    private final PublicKey overrideKey;

    /**
     * @param keysByVersion public keys by {@code Signature-key-version} (e.g. {@code "6.0"})
     * @param overrideKey   when non-null, used for every version instead of {@code keysByVersion}
     */
    public SaltEdgeCallbackVerifier(Map<String, PublicKey> keysByVersion, PublicKey overrideKey) {
        Objects.requireNonNull(keysByVersion, "keysByVersion");
        Map<String, PublicKey> normalized = new LinkedHashMap<>();
        keysByVersion.forEach((version, key) -> normalized.put(normalizeVersion(version), key));
        this.keysByVersion = Map.copyOf(normalized);
        this.overrideKey = overrideKey;
        if (this.keysByVersion.isEmpty() && overrideKey == null) {
            throw new IllegalArgumentException("At least one Salt Edge callback public key is required");
        }
    }

    /** Verifier with the bundled documented Salt Edge callback keys. */
    public static SaltEdgeCallbackVerifier withBundledKeys() {
        return withConfiguredKey(null);
    }

    /**
     * Verifier with the bundled keys, overridden by {@code configuredPublicKeyPem} when it is not blank.
     */
    public static SaltEdgeCallbackVerifier withConfiguredKey(String configuredPublicKeyPem) {
        Map<String, PublicKey> bundled = new LinkedHashMap<>();
        for (String version : BUNDLED_KEY_VERSIONS) {
            String pem = SaltEdgeKeys.readClasspath(BUNDLED_KEY_LOCATION.formatted(version));
            bundled.put(version, SaltEdgeKeys.parsePublicKey(pem));
        }
        PublicKey override = configuredPublicKeyPem == null || configuredPublicKeyPem.isBlank()
                ? null
                : SaltEdgeKeys.parsePublicKey(configuredPublicKeyPem);
        return new SaltEdgeCallbackVerifier(bundled, override);
    }

    /**
     * @param callbackUrl  the exact {@code Public_Base_URL}-derived URL registered for the endpoint
     * @param rawBody      the request body bytes exactly as received
     * @param signatureB64 the {@code Signature} header
     * @param keyVersion   the {@code Signature-key-version} header
     * @return {@code true} only when the signature is valid for {@code callbackUrl|rawBody}
     */
    public boolean verify(String callbackUrl, byte[] rawBody, String signatureB64, String keyVersion) {
        if (callbackUrl == null || rawBody == null || signatureB64 == null || signatureB64.isBlank()) {
            return false;
        }
        PublicKey key = resolveKey(keyVersion);
        if (key == null) {
            return false;
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signatureB64.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SaltEdgeRequestSigner.ALGORITHM);
            signature.initVerify(key);
            signature.update(signedPayload(callbackUrl, rawBody));
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code callbackUrl} (UTF-8) + {@code '|'} + raw body bytes. */
    static byte[] signedPayload(String callbackUrl, byte[] rawBody) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(callbackUrl.length() + 1 + rawBody.length);
        out.writeBytes(callbackUrl.getBytes(StandardCharsets.UTF_8));
        out.write('|');
        out.writeBytes(rawBody);
        return out.toByteArray();
    }

    private PublicKey resolveKey(String keyVersion) {
        if (overrideKey != null) {
            return overrideKey;
        }
        if (keyVersion == null || keyVersion.isBlank()) {
            return null;
        }
        return keysByVersion.get(normalizeVersion(keyVersion));
    }

    /** {@code "6"} and {@code " 6.0 "} both resolve to {@code "6.0"}. */
    static String normalizeVersion(String version) {
        String v = Objects.requireNonNull(version, "version").trim();
        return v.contains(".") ? v : v + ".0";
    }
}
