package com.foremen.controller.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.JwtAuthenticationEntryPoint;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.SecurityConfig;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.saltedge.SaltEdgeCallbackReceived;
import com.foremen.service.bank.connector.saltedge.SaltEdgeCallbackVerifier;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.testsupport.MockMvcSecurityConfig;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FOR-07-04 task 7.3 (R10.1–10.3, 21.5, 24.2): {@link SaltEdgeCallbackController} through the real
 * {@link SecurityConfig} filter chain, unauthenticated. The verifier uses a generated test key pair
 * injected through the configured-key override; the callback URL comes from {@link PublicBaseUrl}.
 */
@WebMvcTest(controllers = SaltEdgeCallbackController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        PermissionResolver.class, MockMvcSecurityConfig.class, SaltEdgeCallbackControllerTest.Keys.class})
@RecordApplicationEvents
class SaltEdgeCallbackControllerTest {

    static final String BASE = "https://dev.foremen.eu";
    static final KeyPair KEY_PAIR = generate();

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean
        PublicBaseUrl publicBaseUrl() {
            return new PublicBaseUrl(BASE);
        }

        @Bean
        SaltEdgeCallbackVerifier saltEdgeCallbackVerifier() {
            return SaltEdgeCallbackVerifier.withConfiguredKey(pem(KEY_PAIR.getPublic().getEncoded()));
        }
    }

    @MockitoBean
    private MessageResolver messageResolver;

    @MockitoBean
    private ForemenPermissionEvaluator permissionEvaluator;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents events;

    private static final byte[] SUCCESS_BODY = ("{\"data\":{\"connection_id\":\"111\",\"customer_id\":\"222\","
            + "\"custom_fields\":{\"foremenConnectionId\":\"42\"},\"stage\":\"finish\"},"
            + "\"meta\":{\"version\":\"6\",\"time\":\"2026-01-01T00:00:00Z\"}}").getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("valid signature, unauthenticated → 200 {} and the callback event is published")
    void validSignatureAccepted() throws Exception {
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", sign(BASE + "/api/bank-feed/saltedge/callbacks/success", SUCCESS_BODY))
                        .header("Signature-key-version", "6.0")
                        .content(SUCCESS_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        List<SaltEdgeCallbackReceived> published = events.stream(SaltEdgeCallbackReceived.class).toList();
        assertThat(published).containsExactly(new SaltEdgeCallbackReceived(
                "success", "111", "222", "42", "finish", null, null));
    }

    @Test
    @DisplayName("missing Signature header → 401, nothing published")
    void missingSignatureRejected() throws Exception {
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized());
        assertThat(events.stream(SaltEdgeCallbackReceived.class)).isEmpty();
    }

    @Test
    @DisplayName("garbage / tampered / other-endpoint signature → 401, nothing published")
    void wrongSignatureRejected() throws Exception {
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", "AAAA")
                        .content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized());

        byte[] tampered = Arrays.copyOf(SUCCESS_BODY, SUCCESS_BODY.length);
        tampered[20] = (byte) (tampered[20] == '1' ? '2' : '1');
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", sign(BASE + "/api/bank-feed/saltedge/callbacks/success", SUCCESS_BODY))
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        // a signature made for the fail URL must not verify on the success URL
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", sign(BASE + "/api/bank-feed/saltedge/callbacks/fail", SUCCESS_BODY))
                        .content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized());

        assertThat(events.stream(SaltEdgeCallbackReceived.class)).isEmpty();
    }

    @Test
    @DisplayName("body larger than 1 MB → 413, nothing published")
    void oversizeBodyRejected() throws Exception {
        byte[] big = new byte[SaltEdgeCallbackController.MAX_BODY_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", sign(BASE + "/api/bank-feed/saltedge/callbacks/notify", big))
                        .content(big))
                .andExpect(status().isPayloadTooLarge());
        assertThat(events.stream(SaltEdgeCallbackReceived.class)).isEmpty();
    }

    @Test
    @DisplayName("unknown callback type → 404")
    void unknownTypeNotFound() throws Exception {
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SUCCESS_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("verified body without a data object → 200 no-op")
    void verifiedButEmptyDataIsNoOp() throws Exception {
        byte[] body = "{\"meta\":{}}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/bank-feed/saltedge/callbacks/destroy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", sign(BASE + "/api/bank-feed/saltedge/callbacks/destroy", body))
                        .content(body))
                .andExpect(status().isOk());
        assertThat(events.stream(SaltEdgeCallbackReceived.class)).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String sign(String callbackUrl, byte[] body) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(KEY_PAIR.getPrivate());
            signature.update((callbackUrl + "|").getBytes(StandardCharsets.UTF_8));
            signature.update(body);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(byte[] der) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----\n";
    }
}
