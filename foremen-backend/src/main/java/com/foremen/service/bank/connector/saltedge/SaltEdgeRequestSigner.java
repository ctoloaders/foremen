package com.foremen.service.bank.connector.saltedge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Signs outgoing Salt Edge API requests (FOR-07-04, R8.5).
 *
 * <p>{@code Signature = base64(RSA-SHA256(privateKey, "Expires-at|METHOD|full_url|body"))}, where
 * {@code full_url} includes the query string exactly as sent and {@code body} is the exact request
 * body string (empty for GET). The public half of the key is uploaded to the Salt Edge dashboard
 * (Keys &amp; Secrets → Public key).
 *
 * <p>Pure and thread-safe: a fresh {@link Signature} instance is created per call. The private key
 * is never logged or exposed.
 */
public final class SaltEdgeRequestSigner {

    static final String ALGORITHM = "SHA256withRSA";

    private final PrivateKey privateKey;

    public SaltEdgeRequestSigner(PrivateKey privateKey) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    }

    /** Builds a signer from a PKCS#8 ({@code BEGIN PRIVATE KEY}) or PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) PEM. */
    public static SaltEdgeRequestSigner fromPem(String privateKeyPem) {
        return new SaltEdgeRequestSigner(SaltEdgeKeys.parsePrivateKey(privateKeyPem));
    }

    /**
     * The exact string that is signed: {@code expiresAt|METHOD|fullUrl|body}; a {@code null} body is
     * treated as empty and the method is upper-cased.
     */
    public static String canonicalString(long expiresAt, String method, String fullUrl, String body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(fullUrl, "fullUrl");
        return expiresAt + "|" + method.toUpperCase(Locale.ROOT) + "|" + fullUrl + "|" + (body == null ? "" : body);
    }

    /** Returns the base64 value of the {@code Signature} header for the given request. */
    public String sign(long expiresAt, String method, String fullUrl, String body) {
        byte[] payload = canonicalString(expiresAt, method, fullUrl, body).getBytes(StandardCharsets.UTF_8);
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Salt Edge request signing failed", e);
        }
    }
}
