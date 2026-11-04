package com.foremen.service.bank.connector.saltedge;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7 (FOR-07-04): signature round-trip — with a generated test key pair, a signature made over
 * the documented string verifies, and flipping any single byte (of the body, the URL or the signature)
 * makes verification fail.
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 7: Signature round-trip
 *
 * <p><b>Validates: Requirements 8.5, 10.2</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 7: Signature round-trip")
class SaltEdgeSignaturePropertyTest {

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final String KEY_VERSION = "6.0";

    private final SaltEdgeCallbackVerifier verifier =
            new SaltEdgeCallbackVerifier(Map.of(KEY_VERSION, KEY_PAIR.getPublic()), null);
    private final SaltEdgeRequestSigner signer = new SaltEdgeRequestSigner(KEY_PAIR.getPrivate());

    record Callback(String url, byte[] body) {
    }

    record Request(long expiresAt, String method, String url, String body) {
    }

    @Provide
    Arbitrary<Callback> callbacks() {
        Arbitrary<String> type = Arbitraries.of("success", "fail", "notify", "destroy");
        Arbitrary<String> host = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
        Arbitrary<byte[]> body = Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(512);
        return Combinators.combine(host, type, body)
                .as((h, t, b) -> new Callback("https://" + h + ".foremen.eu/api/bank-feed/saltedge/callbacks/" + t, b));
    }

    @Provide
    Arbitrary<Request> requests() {
        Arbitrary<Long> expiresAt = Arbitraries.longs().between(1_600_000_000L, 2_000_000_000L);
        Arbitrary<String> method = Arbitraries.of("GET", "POST", "PUT", "DELETE");
        Arbitrary<String> path = Arbitraries.strings().alpha().numeric().withChars('/', '?', '=', '&')
                .ofMinLength(1).ofMaxLength(60);
        Arbitrary<String> body = Arbitraries.strings().ofMaxLength(300).injectNull(0.2);
        return Combinators.combine(expiresAt, method, path, body)
                .as((e, m, p, b) -> new Request(e, m, "https://www.saltedge.com/api/v6/" + p, b));
    }

    // ---- Callback verifier -----------------------------------------------------------------------

    @Property(tries = 200)
    void callbackRoundTripVerifies(@ForAll("callbacks") Callback cb) {
        assertThat(verifier.verify(cb.url(), cb.body(), signCallback(cb), KEY_VERSION)).isTrue();
    }

    @Property(tries = 200)
    void anyBodyByteFlipFailsVerification(@ForAll("callbacks") Callback cb,
                                          @ForAll @IntRange(min = 0, max = 511) int position,
                                          @ForAll @IntRange(min = 1, max = 255) int mask) {
        String sig = signCallback(cb);
        byte[] tampered = cb.body().clone();
        int i = position % tampered.length;
        tampered[i] = (byte) (tampered[i] ^ mask);

        assertThat(verifier.verify(cb.url(), tampered, sig, KEY_VERSION)).isFalse();
    }

    @Property(tries = 200)
    void anyUrlByteFlipFailsVerification(@ForAll("callbacks") Callback cb,
                                         @ForAll @IntRange(min = 0, max = 200) int position,
                                         @ForAll @IntRange(min = 1, max = 0x7F) int mask) {
        String sig = signCallback(cb);
        char[] chars = cb.url().toCharArray();
        int i = position % chars.length;
        // URL is ASCII; an XOR within 0..0x7F keeps it ASCII, so one char flip = one byte flip.
        chars[i] = (char) (chars[i] ^ mask);

        assertThat(verifier.verify(new String(chars), cb.body(), sig, KEY_VERSION)).isFalse();
    }

    @Property(tries = 200)
    void anySignatureByteFlipFailsVerification(@ForAll("callbacks") Callback cb,
                                               @ForAll @IntRange(min = 0, max = 255) int position,
                                               @ForAll @IntRange(min = 1, max = 255) int mask) {
        byte[] sig = Base64.getDecoder().decode(signCallback(cb));
        int i = position % sig.length;
        sig[i] = (byte) (sig[i] ^ mask);

        assertThat(verifier.verify(cb.url(), cb.body(), Base64.getEncoder().encodeToString(sig), KEY_VERSION))
                .isFalse();
    }

    // ---- Request signer --------------------------------------------------------------------------

    @Property(tries = 200)
    void requestSignatureRoundTripsAndDetectsAnyByteFlip(@ForAll("requests") Request r,
                                                         @ForAll @IntRange(min = 0, max = 2000) int position,
                                                         @ForAll @IntRange(min = 1, max = 255) int mask)
            throws Exception {
        byte[] signature = Base64.getDecoder().decode(signer.sign(r.expiresAt(), r.method(), r.url(), r.body()));
        byte[] payload = (r.expiresAt() + "|" + r.method() + "|" + r.url() + "|" + (r.body() == null ? "" : r.body()))
                .getBytes(StandardCharsets.UTF_8);

        assertThat(verifyRaw(payload, signature)).isTrue();

        byte[] tampered = payload.clone();
        int i = position % tampered.length;
        tampered[i] = (byte) (tampered[i] ^ mask);
        assertThat(verifyRaw(tampered, signature)).isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String signCallback(Callback cb) {
        return SaltEdgeSignatureTest.signCallback(KEY_PAIR.getPrivate(), cb.url(), cb.body());
    }

    private static boolean verifyRaw(byte[] payload, byte[] signature) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(KEY_PAIR.getPublic());
        s.update(payload);
        return s.verify(signature);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
