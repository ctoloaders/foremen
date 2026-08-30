package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link ForemenControllerAdvice} resolves auth message codes per
 * request locale (Requirement 15.2). An endpoint throws a ForemenApiException with
 * an auth message code and the resolved $.message must match the localized text for
 * the request's Accept-Language.
 */
@SpringJUnitWebConfig(AuthMessageLocalizationIntegrationTest.TestWebConfig.class)
class AuthMessageLocalizationIntegrationTest {

    // Exact strings from messages.properties / messages_ru.properties for
    // error.auth.invalid.credentials.
    private static final String PL_INVALID_CREDENTIALS = "Nieprawidłowy adres email lub hasło.";
    private static final String RU_INVALID_CREDENTIALS = "Неверный адрес электронной почты или пароль.";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Configuration
    @EnableWebMvc
    @Import({ForemenControllerAdvice.class, AuthExceptionTestController.class})
    static class TestWebConfig {

        @Bean
        public ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setUseCodeAsDefaultMessage(true);
            return source;
        }

        @Bean
        public MessageResolver messageResolver(ResourceBundleMessageSource messageSource) {
            return new MessageResolver(messageSource);
        }

        @Bean
        public LocaleResolver localeResolver() {
            AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
            resolver.setDefaultLocale(Locale.of("pl"));
            return resolver;
        }
    }

    @RestController
    @RequestMapping("/test")
    static class AuthExceptionTestController {

        @GetMapping("/auth-invalid-credentials")
        public void throwInvalidCredentials() {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.invalid.credentials");
        }
    }

    @Test
    @DisplayName("Default locale (no Accept-Language) resolves auth code to Polish message with 401")
    void defaultLocaleResolvesAuthCodeToPolish() throws Exception {
        mockMvc.perform(get("/test/auth-invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(PL_INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("Accept-Language: pl resolves auth code to Polish message with 401")
    void polishLocaleResolvesAuthCodeToPolish() throws Exception {
        mockMvc.perform(get("/test/auth-invalid-credentials")
                        .header("Accept-Language", "pl"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(PL_INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("Accept-Language: ru resolves auth code to Russian message with 401")
    void russianLocaleResolvesAuthCodeToRussian() throws Exception {
        mockMvc.perform(get("/test/auth-invalid-credentials")
                        .header("Accept-Language", "ru"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(RU_INVALID_CREDENTIALS));
    }
}
