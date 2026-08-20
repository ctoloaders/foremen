package com.foremen.controller.property;

import com.foremen.config.i18n.ForemenLocaleInterceptor;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 4: Locale Interceptor Round-Trip
 *
 * For any valid locale language tag string set in the "foremen-language" header,
 * after ForemenLocaleInterceptor.preHandle executes, LocaleContextHolder.getLocale()
 * SHALL return a Locale matching the header value. After afterCompletion executes,
 * LocaleContextHolder.getLocale() SHALL return to the default locale (the locale context
 * is reset), preventing locale leaking between requests.
 *
 * Validates: Requirements 15.1, 15.3
 */
@Tag("Feature: FOR-01-07-crud-controller, Property 4: Locale Interceptor Round-Trip")
class LocaleInterceptorPropertyTest {

    private final ForemenLocaleInterceptor interceptor = new ForemenLocaleInterceptor();

    @AfterProperty
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Property(tries = 100)
    void preHandleSetsLocaleFromHeader(@ForAll("localeTags") String tag) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(ForemenLocaleInterceptor.LOCALE_HEADER, tag);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result, "preHandle should always return true");

        Locale expected = Locale.forLanguageTag(tag);
        Locale actual = LocaleContextHolder.getLocale();
        assertEquals(expected, actual,
                "LocaleContextHolder locale should match Locale.forLanguageTag(\"" + tag + "\")");
    }

    @Property(tries = 100)
    void afterCompletionResetsLocaleToDefault(@ForAll("localeTags") String tag) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(ForemenLocaleInterceptor.LOCALE_HEADER, tag);

        // Set locale via preHandle
        interceptor.preHandle(request, response, new Object());

        // Now call afterCompletion
        interceptor.afterCompletion(request, response, new Object(), null);

        Locale actual = LocaleContextHolder.getLocale();
        assertEquals(Locale.getDefault(), actual,
                "After afterCompletion, locale should be reset to default");
    }

    @Provide
    Arbitrary<String> localeTags() {
        return Arbitraries.of(
                "ru", "pl", "en", "de", "fr", "uk", "ja",
                "es", "it", "pt", "zh", "ko", "ar", "hi",
                "nl", "sv", "da", "fi", "nb", "cs", "sk"
        );
    }
}
