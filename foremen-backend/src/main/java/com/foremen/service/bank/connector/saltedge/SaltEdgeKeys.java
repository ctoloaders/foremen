package com.foremen.service.bank.connector.saltedge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM helpers for the Salt Edge RSA keys (FOR-07-04, R8.5 / R10.2).
 *
 * <p>Accepts the formats Salt Edge documents and {@code openssl} produces:
 * <ul>
 *   <li>public keys — X.509 {@code -----BEGIN PUBLIC KEY-----} (lines may be indented, as in the docs);</li>
 *   <li>private keys — PKCS#8 {@code -----BEGIN PRIVATE KEY-----} or PKCS#1
 *       {@code -----BEGIN RSA PRIVATE KEY-----} (the {@code openssl genrsa} 1.x default).</li>
 * </ul>
 * Never logs key material.
 */
final class SaltEdgeKeys {

    private static final String PKCS1_PRIVATE_HEADER = "BEGIN RSA PRIVATE KEY";

    /** DER of AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }. */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D,
            0x01, 0x01, 0x01, 0x05, 0x00
    };

    private SaltEdgeKeys() {
    }

    static PublicKey parsePublicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid RSA public key PEM", e);
        }
    }

    static PrivateKey parsePrivateKey(String pem) {
        byte[] der = decodePem(pem);
        if (pem.contains(PKCS1_PRIVATE_HEADER)) {
            der = wrapPkcs1InPkcs8(der);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid RSA private key PEM", e);
        }
    }

    static String readClasspath(String location) {
        try (InputStream in = SaltEdgeKeys.class.getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource: " + location, e);
        }
    }

    /** Strips the {@code -----BEGIN/END ...-----} armour and all whitespace, then base64-decodes. */
    static byte[] decodePem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM is blank");
        }
        String body = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalArgumentException("PEM has no content");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM content is not valid base64", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING pkcs1 }. */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(new byte[] {0x02, 0x01, 0x00});
        content.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        content.write(0x04);
        content.writeBytes(derLength(pkcs1.length));
        content.writeBytes(pkcs1);
        byte[] inner = content.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.writeBytes(derLength(inner.length));
        out.writeBytes(inner);
        return out.toByteArray();
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length <= 0xFF) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length <= 0xFFFF) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        return new byte[] {(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }
}
