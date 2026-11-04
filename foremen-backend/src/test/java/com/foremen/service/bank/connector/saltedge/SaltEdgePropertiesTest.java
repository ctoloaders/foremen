package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.PublicBaseUrl;
import org.junit.jupiter.api.Test;

/** FOR-07-04 task 5.1 — availability rules of {@link SaltEdgeProperties} (R8.2, R8.3). */
class SaltEdgePropertiesTest {

    private static final PublicBaseUrl HTTPS = new PublicBaseUrl("https://dev.foremen.eu");

    private static SaltEdgeProperties props(boolean enabled, SaltEdgeMode mode, String appId, String secret,
                                            String leadEmail, Boolean includeFake) {
        return new SaltEdgeProperties(enabled, mode, null, appId, secret, null, null, null, leadEmail, 0,
                includeFake, null, null, null);
    }

    @Test
    void availableWhenEnabledWithKeysIdentityAndBaseUrl() {
        assertThat(props(true, SaltEdgeMode.CLIENT, "a", "s", null, null).isAvailable(HTTPS)).isTrue();
        assertThat(props(true, SaltEdgeMode.PARTNER, "a", "s", "finance@example.com", false).isAvailable(HTTPS))
                .isTrue();
    }

    @Test
    void unavailableWhenDisabledOrKeysMissing() {
        assertThat(props(false, SaltEdgeMode.CLIENT, "a", "s", null, null).isAvailable(HTTPS)).isFalse();
        assertThat(props(true, SaltEdgeMode.CLIENT, null, "s", null, null).configurationProblems(HTTPS))
                .anyMatch(p -> p.contains("app-id"));
        assertThat(props(true, SaltEdgeMode.CLIENT, "a", " ", null, null).configurationProblems(HTTPS))
                .anyMatch(p -> p.contains("secret"));
    }

    @Test
    void partnerModeRequiresLeadEmail() {
        assertThat(props(true, SaltEdgeMode.PARTNER, "a", "s", null, null).configurationProblems(HTTPS))
                .anyMatch(p -> p.contains("lead-email"));
    }

    @Test
    void publicBaseUrlBlankOrHttpInLiveMakesItUnavailable() {
        SaltEdgeProperties test = props(true, SaltEdgeMode.CLIENT, "a", "s", null, true);
        SaltEdgeProperties live = props(true, SaltEdgeMode.CLIENT, "a", "s", null, false);
        PublicBaseUrl http = new PublicBaseUrl("http://localhost:3000");

        assertThat(test.isAvailable(new PublicBaseUrl(""))).isFalse();
        assertThat(test.isAvailable(http)).isTrue();
        assertThat(live.isAvailable(http)).isFalse();
        assertThat(live.configurationProblems(http)).anyMatch(p -> p.contains("HTTPS"));
    }

    @Test
    void problemsNeverContainSecretValues() {
        assertThat(props(true, SaltEdgeMode.PARTNER, "app-123", "secret-456", null, false)
                .configurationProblems(new PublicBaseUrl("")).toString())
                .doesNotContain("app-123", "secret-456");
    }
}
