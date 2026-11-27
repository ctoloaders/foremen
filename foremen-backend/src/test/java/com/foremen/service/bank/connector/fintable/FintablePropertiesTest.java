package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.bank.PublicBaseUrl;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FOR-07-04a (Requirements 2.1, 2.2, 2.4): availability matrix of {@link FintableProperties}.
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.4</b>
 */
class FintablePropertiesTest {

    private static final String TOKEN = "ft_test_token_value";
    private static final PublicBaseUrl PUBLIC = new PublicBaseUrl("https://dev.foremen.eu");
    private static final PublicBaseUrl BLANK_PUBLIC = new PublicBaseUrl("");

    private static FintableProperties props(boolean enabled, String baseUrl, boolean insecure, String token) {
        return new FintableProperties(enabled, baseUrl, insecure, token, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("valid configuration is available")
    void valid() {
        FintableProperties p = props(true, "https://fintable.io/api/v2", false, TOKEN);
        assertThat(p.problems()).isEmpty();
        assertThat(p.isAvailable(PUBLIC)).isTrue();
    }

    @Test
    @DisplayName("flag off is unavailable even with a complete configuration")
    void flagOff() {
        assertThat(props(false, "https://fintable.io/api/v2", false, TOKEN).isAvailable(PUBLIC)).isFalse();
    }

    @Test
    @DisplayName("blank token is unavailable and the problem names the property, not a value")
    void blankToken() {
        FintableProperties p = props(true, "https://fintable.io/api/v2", false, "  ");
        assertThat(p.isAvailable(PUBLIC)).isFalse();
        assertThat(p.problems()).hasSize(1);
        assertThat(p.problems().get(0)).contains("foremen.bank-feed.fintable.api-token");
    }


    @Test
    @DisplayName("HTTP base URL is rejected unless allow-insecure-base-url is set")
    void httpBaseUrl() {
        FintableProperties rejected = props(true, "http://localhost:9999/api/v2", false, TOKEN);
        assertThat(rejected.isAvailable(PUBLIC)).isFalse();
        assertThat(rejected.problems()).singleElement().asString()
                .contains("foremen.bank-feed.fintable.base-url").contains("HTTPS");

        assertThat(props(true, "http://localhost:9999/api/v2", true, TOKEN).isAvailable(PUBLIC)).isTrue();
    }

    @Test
    @DisplayName("non-URL base URL is a problem even with allow-insecure-base-url")
    void invalidBaseUrl() {
        assertThat(props(true, "ftp://host/x", true, TOKEN).problems()).hasSize(1);
        assertThat(props(true, "not a url", true, TOKEN).problems()).hasSize(1);
    }

    @Test
    @DisplayName("blank public base URL makes the connector unavailable")
    void blankPublicBaseUrl() {
        FintableProperties p = props(true, "https://fintable.io/api/v2", false, TOKEN);
        assertThat(p.problems()).isEmpty();
        assertThat(p.isAvailable(BLANK_PUBLIC)).isFalse();
        assertThat(p.isAvailable(null)).isFalse();
        assertThat(p.availabilityProblems(BLANK_PUBLIC)).containsExactly(PublicBaseUrl.PROPERTY + " is not set");
    }

    @Test
    @DisplayName("problems never contain property values")
    void problemsHideValues() {
        FintableProperties p = props(true, "http://secret-host.example/api", false, TOKEN);
        assertThat(String.join("|", p.availabilityProblems(BLANK_PUBLIC)))
                .doesNotContain(TOKEN).doesNotContain("secret-host");
        assertThat(p.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("blank values fall back to the documented defaults")
    void defaults() {
        FintableProperties p = new FintableProperties(false, "", false, "", null, "", null, List.of(),
                List.of(), List.of(), null, null, null, null, null);
        assertThat(p.baseUrl()).isEqualTo("https://fintable.io/api/v2");
        assertThat(p.httpTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.countryCodes()).containsExactly("PL");
        assertThat(p.providerPreference()).containsExactly("ENABLEBANKING", "NORDIGEN");
        assertThat(p.popularBanks()).contains("PKO", "Alior");
        assertThat(p.syncWait()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.syncPollInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.manualSyncInterval()).isEqualTo(Duration.ofHours(12));
        assertThat(p.cursorOverlap()).isEqualTo(Duration.ofHours(1));
        assertThat(p.tokenWarnDays()).isEqualTo(30);
    }
}
