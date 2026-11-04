package com.foremen.service.bank.secret;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.secret.BankSecretCipher.Encrypted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BankSecretCipher} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 2.1, 2.5</b>
 */
class BankSecretCipherTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private final BankSecretCipher cipher = new BankSecretCipher(randomKey());

    private static Map<String, String> sample() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("clientId", "client-123");
        m.put("clientSecret", "s3cr3t-ąęółżź-\"quoted\"\n");
        return m;
    }

    @Test
    void roundTripRestoresMap() {
        Encrypted enc = cipher.encrypt(sample());

        assertThat(cipher.isConfigured()).isTrue();
        assertThat(enc.keyVersion()).isEqualTo((short) 1);
        assertThat(enc.iv()).hasSize(12);
        assertThat(cipher.decrypt(enc)).isEqualTo(sample());
    }

    @Test
    void emptyMapRoundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt(Map.of()))).isEmpty();
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        Encrypted enc = cipher.encrypt(sample());
        String asText = new String(enc.ciphertext(), StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain("client-123").doesNotContain("clientSecret");
    }

    @Test
    void everyEncryptionUsesFreshIv() {
        Encrypted a = cipher.encrypt(sample());
        Encrypted b = cipher.encrypt(sample());
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void tamperedCiphertextIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        byte[] ct = enc.ciphertext();
        for (int i = 0; i < ct.length; i++) {
            byte[] mutated = ct.clone();
            mutated[i] ^= 0x01;
            Encrypted tampered = new Encrypted(mutated, enc.iv(), enc.keyVersion());
            assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(BankSecretCipherException.class);
        }
    }

    @Test
    void tamperedIvIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        byte[] iv = enc.iv();
        iv[0] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(new Encrypted(enc.ciphertext(), iv, enc.keyVersion())))
                .isInstanceOf(BankSecretCipherException.class);
    }

    @Test
    void truncatedCiphertextIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        byte[] ct = enc.ciphertext();
        byte[] truncated = java.util.Arrays.copyOf(ct, ct.length - 1);
        assertThatThrownBy(() -> cipher.decrypt(new Encrypted(truncated, enc.iv(), enc.keyVersion())))
                .isInstanceOf(BankSecretCipherException.class);
    }

    @Test
    void wrongKeyIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        BankSecretCipher other = new BankSecretCipher(randomKey());
        assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(BankSecretCipherException.class);
    }

    @Test
    void unknownKeyVersionIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        assertThatThrownBy(() -> cipher.decrypt(new Encrypted(enc.ciphertext(), enc.iv(), (short) 2)))
                .isInstanceOf(BankSecretCipherException.class);
    }

    @Test
    void malformedIvIsRejected() {
        Encrypted enc = cipher.encrypt(sample());
        assertThatThrownBy(() -> cipher.decrypt(new Encrypted(enc.ciphertext(), new byte[8], enc.keyVersion())))
                .isInstanceOf(BankSecretCipherException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not base64!!", "AAAAAAAAAAAAAAAAAAAAAA=="}) // last one = 16 bytes
    void missingOrInvalidKeyMeansNotConfigured(String key) {
        BankSecretCipher unconfigured = new BankSecretCipher(key);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.encrypt(sample()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.bank.secrets.notConfigured");
                });
        Encrypted enc = cipher.encrypt(sample());
        assertThatThrownBy(() -> unconfigured.decrypt(enc)).isInstanceOf(ForemenApiException.class);
    }

    @Test
    void toStringDoesNotLeakBytes() {
        assertThat(cipher.encrypt(sample()).toString()).doesNotContain("client");
    }
}
