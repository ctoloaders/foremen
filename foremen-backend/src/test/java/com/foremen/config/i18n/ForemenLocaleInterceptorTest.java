package com.foremen.config.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ForemenLocaleInterceptorTest {

    private ForemenLocaleInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new ForemenLocaleInterceptor();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        LocaleContextHolder.resetLocaleContext();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("preHandle with 'foremen-language: ru' sets locale to Russian")
    void preHandleWithRuHeaderSetsRussianLocale() {
        request.addHeader(ForemenLocaleInterceptor.LOCALE_HEADER, "ru");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("ru"));
    }

    @Test
    @DisplayName("preHandle with 'foremen-language: pl' sets locale to Polish")
    void preHandleWithPlHeaderSetsPolishLocale() {
        request.addHeader(ForemenLocaleInterceptor.LOCALE_HEADER, "pl");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("pl"));
    }

    @Test
    @DisplayName("preHandle without header leaves locale unchanged (default)")
    void preHandleWithoutHeaderLeavesLocaleUnchanged() {
        Locale defaultLocale = LocaleContextHolder.getLocale();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(defaultLocale);
    }

    @Test
    @DisplayName("preHandle with blank header leaves locale unchanged")
    void preHandleWithBlankHeaderLeavesLocaleUnchanged() {
        Locale defaultLocale = LocaleContextHolder.getLocale();
        request.addHeader(ForemenLocaleInterceptor.LOCALE_HEADER, "   ");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(defaultLocale);
    }

    @Test
    @DisplayName("afterCompletion resets locale context to default")
    void afterCompletionResetsLocaleContext() {
        // Set a non-default locale first
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("ru"));

        interceptor.afterCompletion(request, response, new Object(), null);

        // After reset, locale should return to system default
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.getDefault());
    }
}
