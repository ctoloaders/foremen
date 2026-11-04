package com.foremen.service.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** FOR-07-04 task 5.1 — {@link PublicBaseUrl} (R8.2, R24.6; design §Environment configuration). */
class PublicBaseUrlTest {

    @Test
    void buildsReturnAndCallbackUrlsAndStripsTrailingSlash() {
        PublicBaseUrl url = new PublicBaseUrl(" https://dev.foremen.eu/ ");

        assertThat(url.value()).isEqualTo("https://dev.foremen.eu");
        assertThat(url.isConfigured()).isTrue();
        assertThat(url.isHttps()).isTrue();
        assertThat(url.returnUrl(42L)).isEqualTo("https://dev.foremen.eu/bank/connections/return?connectionId=42");
        assertThat(url.callbackUrl("success"))
                .isEqualTo("https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/success");
        assertThat(url.callbackUrl("destroy"))
                .isEqualTo("https://dev.foremen.eu/api/bank-feed/saltedge/callbacks/destroy");
        assertThat(url.problem(true)).isEmpty();
        assertThat(url.problem(false)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankHasNoDefaultAndIsAProblem(String raw) {
        PublicBaseUrl url = new PublicBaseUrl(raw);

        assertThat(url.isConfigured()).isFalse();
        assertThat(url.value()).isEmpty();
        assertThat(url.problem(false)).hasValueSatisfying(p -> assertThat(p).contains("is not set"));
        assertThat(url.problem(true)).isPresent();
        assertThatThrownBy(() -> url.returnUrl(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> url.callbackUrl("success")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void httpIsAllowedOutsideLiveOnly() {
        PublicBaseUrl url = new PublicBaseUrl("http://localhost:3000");

        assertThat(url.isConfigured()).isTrue();
        assertThat(url.isHttps()).isFalse();
        assertThat(url.problem(false)).isEmpty();
        assertThat(url.problem(true)).hasValueSatisfying(p -> assertThat(p).contains("HTTPS"));
        assertThat(url.returnUrl(7L)).isEqualTo("http://localhost:3000/bank/connections/return?connectionId=7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev.foremen.eu", "ftp://dev.foremen.eu", "https://", "https://dev.foremen.eu?x=1",
            "https://dev foremen.eu"})
    void malformedValuesAreProblems(String raw) {
        PublicBaseUrl url = new PublicBaseUrl(raw);

        assertThat(url.isConfigured()).isFalse();
        assertThat(url.problem(false)).isPresent();
        assertThatThrownBy(() -> url.returnUrl(1L)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../admin", "Success", "a/b"})
    void rejectsInvalidCallbackTypes(String type) {
        PublicBaseUrl url = new PublicBaseUrl("https://dev.foremen.eu");

        assertThatThrownBy(() -> url.callbackUrl(type)).isInstanceOf(IllegalArgumentException.class);
    }
}
