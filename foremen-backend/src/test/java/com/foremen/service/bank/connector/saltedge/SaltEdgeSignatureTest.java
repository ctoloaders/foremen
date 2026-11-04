package com.foremen.service.bank.connector.saltedge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SaltEdgeRequestSigner} and {@link SaltEdgeCallbackVerifier} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 8.5, 10.2</b>
 */
class SaltEdgeSignatureTest {

    private static final String CALLBACK_URL = "https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/success";
    private static final byte[] BODY = ("{\"data\":{\"connection_id\":\"111111111111111111\",\"customer_id\":"
            + "\"222222222222222222\",\"custom_fields\":{}},\"meta\":{\"version\":\"6\",\"time\":\"2020-07-07T13:00:28Z\"}}")
            .getBytes(StandardCharsets.UTF_8);

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    // ---- SaltEdgeRequestSigner -------------------------------------------------------------------

    @Test
    void canonicalStringJoinsPartsWithPipeAndUpperCasesMethod() {
        assertThat(SaltEdgeRequestSigner.canonicalString(1_700_000_060L, "post",
                "https://www.saltedge.com/api/v6/customers", "{\"data\":{}}"))
                .isEqualTo("1700000060|POST|https://www.saltedge.com/api/v6/customers|{\"data\":{}}");
    }

    @Test
    void canonicalStringUsesEmptyBodyForGet() {
        assertThat(SaltEdgeRequestSigner.canonicalString(1_700_000_060L, "GET",
                "https://www.saltedge.com/api/v6/accounts?connection_id=1&from_id=2", null))
                .isEqualTo("1700000060|GET|https://www.saltedge.com/api/v6/accounts?connection_id=1&from_id=2|");
    }

    @Test
    void signatureVerifiesAgainstPublicKeyOverCanonicalString() throws Exception {
        SaltEdgeRequestSigner signer = new SaltEdgeRequestSigner(keyPair.getPrivate());
        String url = "https://www.saltedge.com/api/v6/connections/connect";
        String body = "{\"data\":{\"customer_id\":\"42\"}}";

        String signatureB64 = signer.sign(1_700_000_060L, "POST", url, body);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(("1700000060|POST|" + url + "|" + body).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signatureB64))).isTrue();
    }

    @Test
    void fromPemAcceptsPkcs8AndPkcs1() {
        byte[] pkcs8 = keyPair.getPrivate().getEncoded();
        // For a 2048-bit key the PKCS#8 wrapper is a fixed 26-byte header around the PKCS#1 structure.
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        String pkcs8Pem = pem("PRIVATE KEY", pkcs8);
        String pkcs1Pem = pem("RSA PRIVATE KEY", pkcs1);

        String expected = new SaltEdgeRequestSigner(keyPair.getPrivate()).sign(1L, "GET", "https://x/y", "");
        // RSASSA-PKCS1-v1_5 is deterministic, so the same key yields the same signature.
        assertThat(SaltEdgeRequestSigner.fromPem(pkcs8Pem).sign(1L, "GET", "https://x/y", "")).isEqualTo(expected);
        assertThat(SaltEdgeRequestSigner.fromPem(pkcs1Pem).sign(1L, "GET", "https://x/y", "")).isEqualTo(expected);
    }

    @Test
    void fromPemRejectsGarbage() {
        assertThatThrownBy(() -> SaltEdgeRequestSigner.fromPem("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SaltEdgeRequestSigner.fromPem(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- SaltEdgeCallbackVerifier ----------------------------------------------------------------

    @Test
    void bundledDocumentedKeysLoadFromClasspath() {
        SaltEdgeCallbackVerifier verifier = SaltEdgeCallbackVerifier.withBundledKeys();
        // A signature from a foreign key never verifies against the documented Salt Edge keys.
        String foreign = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);
        assertThat(verifier.verify(CALLBACK_URL, BODY, foreign, "6.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, BODY, foreign, "5.0")).isFalse();
    }

    @Test
    void acceptsValidSignatureForMatchingKeyVersion() {
        SaltEdgeCallbackVerifier verifier = new SaltEdgeCallbackVerifier(Map.of("6.0", keyPair.getPublic()), null);
        String sig = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);

        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "6.0")).isTrue();
        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "6")).isTrue();
    }

    @Test
    void rejectsUnknownOrMissingKeyVersion() {
        SaltEdgeCallbackVerifier verifier = new SaltEdgeCallbackVerifier(Map.of("6.0", keyPair.getPublic()), null);
        String sig = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);

        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "5.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, null)).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "")).isFalse();
    }

    @Test
    void rejectsWrongUrlBodyOrMalformedSignature() {
        SaltEdgeCallbackVerifier verifier = new SaltEdgeCallbackVerifier(Map.of("6.0", keyPair.getPublic()), null);
        String sig = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);

        assertThat(verifier.verify(CALLBACK_URL.replace("/success", "/fail"), BODY, sig, "6.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, "{}".getBytes(StandardCharsets.UTF_8), sig, "6.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, BODY, "not base64 !!", "6.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, BODY, null, "6.0")).isFalse();
        assertThat(verifier.verify(CALLBACK_URL, null, sig, "6.0")).isFalse();
        assertThat(verifier.verify(null, BODY, sig, "6.0")).isFalse();
    }

    @Test
    void configuredKeyOverridesBundledKeysForEveryVersion() {
        SaltEdgeCallbackVerifier verifier = SaltEdgeCallbackVerifier.withConfiguredKey(
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
        String sig = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);

        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "6.0")).isTrue();
        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "5.0")).isTrue();
    }

    @Test
    void blankConfiguredKeyFallsBackToBundledKeys() {
        SaltEdgeCallbackVerifier verifier = SaltEdgeCallbackVerifier.withConfiguredKey("  ");
        String sig = signCallback(keyPair.getPrivate(), CALLBACK_URL, BODY);
        assertThat(verifier.verify(CALLBACK_URL, BODY, sig, "6.0")).isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    static String signCallback(PrivateKey key, String callbackUrl, byte[] body) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            // Independent of the production payload builder: the documented "callback_url|post_body".
            signature.update((callbackUrl + "|").getBytes(StandardCharsets.UTF_8));
            signature.update(body);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
