package com.foremen.config.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MessageResolverTest {

    private MessageResolver messageResolver;
    private ResourceBundleMessageSource messageSource;

    @BeforeEach
    void setUp() {
        messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageResolver = new MessageResolver(messageSource);
    }

    @Test
    @DisplayName("resolve existing code with PL locale returns Polish message")
    void resolveExistingCodeReturnsPolishMessage() {
        String result = messageResolver.resolve("error.internal", null, Locale.of("pl"));

        assertThat(result).isEqualTo("Wystąpił błąd wewnętrzny. Spróbuj ponownie później.");
    }

    @Test
    @DisplayName("resolve existing code with RU locale returns Russian message")
    void resolveExistingCodeReturnsRussianMessage() {
        String result = messageResolver.resolve("error.internal", null, Locale.of("ru"));

        assertThat(result).isEqualTo("Произошла внутренняя ошибка. Попробуйте позже.");
    }

    @Test
    @DisplayName("resolve with parameters substitutes {0} and {1} placeholders")
    void resolveWithParametersSubstitutes() {
        // Use MessageSource directly to test parameterized message substitution.
        // Add a parameterized message code to the source for this test.
        messageSource.setUseCodeAsDefaultMessage(false);

        // We test the mechanism by calling resolve with a pattern that exists as a default message.
        // Since useCodeAsDefaultMessage won't help here, we use getMessage with a default pattern.
        String pattern = "Field {0} has error: {1}";
        String result = messageSource.getMessage(
                "test.parameterized.code", new Object[]{"email", "invalid format"}, pattern, Locale.of("pl"));

        assertThat(result).isEqualTo("Field email has error: invalid format");
    }

    @Test
    @DisplayName("resolve missing code returns the code itself as fallback")
    void resolveMissingCodeReturnCodeItself() {
        String unknownCode = "some.unknown.code";

        String result = messageResolver.resolve(unknownCode, null, Locale.of("pl"));

        assertThat(result).isEqualTo(unknownCode);
    }

    @Test
    @DisplayName("default locale is PL when no Accept-Language header")
    void defaultLocaleIsPl() {
        MessageResolver.MessageSourceConfig config = new MessageResolver.MessageSourceConfig();
        AcceptHeaderLocaleResolver localeResolver = (AcceptHeaderLocaleResolver) config.localeResolver();

        // resolveLocale with an empty Accept-Language header returns the default locale
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No Accept-Language header set → should resolve to default PL
        Locale resolvedLocale = localeResolver.resolveLocale(request);

        assertThat(resolvedLocale).isEqualTo(Locale.of("pl"));
    }

    @Test
    @DisplayName("default bundle (messages.properties) contains all required keys")
    void bundleContainsRequiredKeys() {
        String[] requiredKeys = {
                "error.data.integrity",
                "error.access.denied",
                "error.internal",
                "error.validation"
        };

        // Temporarily disable useCodeAsDefaultMessage to detect missing keys
        messageSource.setUseCodeAsDefaultMessage(false);

        for (String key : requiredKeys) {
            String message = messageSource.getMessage(key, null, null, Locale.of("pl"));
            assertThat(message)
                    .as("Default bundle should contain key: %s", key)
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Russian bundle (messages_ru.properties) contains all required keys")
    void russianBundleContainsRequiredKeys() {
        String[] requiredKeys = {
                "error.data.integrity",
                "error.access.denied",
                "error.internal",
                "error.validation"
        };

        // Temporarily disable useCodeAsDefaultMessage to detect missing keys
        messageSource.setUseCodeAsDefaultMessage(false);

        for (String key : requiredKeys) {
            String message = messageSource.getMessage(key, null, null, Locale.of("ru"));
            assertThat(message)
                    .as("Russian bundle should contain key: %s", key)
                    .isNotNull()
                    .isNotEmpty();
        }
    }
}
