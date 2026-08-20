package com.foremen.controller.integration;

import com.foremen.config.i18n.ForemenLocaleInterceptor;
import com.foremen.config.web.ForemenWebMvcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ForemenLocaleInterceptor} in a full HTTP context.
 * Verifies that the interceptor correctly resolves the locale from the foremen-language header
 * and that no locale leaking occurs between sequential requests.
 *
 * Validates: Requirements 15.1, 15.2, 15.3
 */
@SpringJUnitWebConfig(LocaleInterceptorIntegrationTest.TestWebConfig.class)
class LocaleInterceptorIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Configuration
    @EnableWebMvc
    @Import({ForemenWebMvcConfig.class, ForemenLocaleInterceptor.class, LocaleTestController.class})
    static class TestWebConfig {
    }

    @RestController
    @RequestMapping("/test/locale")
    static class LocaleTestController {

        @GetMapping
        public String getLocale() {
            return LocaleContextHolder.getLocale().getLanguage();
        }
    }

    @Test
    @DisplayName("GET with foremen-language: ru header returns 'ru' as resolved locale")
    void getWithRuHeaderReturnsRuLocale() throws Exception {
        mockMvc.perform(get("/test/locale")
                        .header("foremen-language", "ru"))
                .andExpect(status().isOk())
                .andExpect(content().string("ru"));
    }

    @Test
    @DisplayName("GET with foremen-language: pl header returns 'pl' as resolved locale")
    void getWithPlHeaderReturnsPlLocale() throws Exception {
        mockMvc.perform(get("/test/locale")
                        .header("foremen-language", "pl"))
                .andExpect(status().isOk())
                .andExpect(content().string("pl"));
    }

    @Test
    @DisplayName("GET without foremen-language header returns system default locale language")
    void getWithoutHeaderReturnsDefaultLocale() throws Exception {
        String defaultLanguage = java.util.Locale.getDefault().getLanguage();

        mockMvc.perform(get("/test/locale"))
                .andExpect(status().isOk())
                .andExpect(content().string(defaultLanguage));
    }

    @Test
    @DisplayName("Sequential requests with different locales do not leak between requests")
    void sequentialRequestsDoNotLeak() throws Exception {
        String defaultLanguage = java.util.Locale.getDefault().getLanguage();

        // First request with "ru"
        mockMvc.perform(get("/test/locale")
                        .header("foremen-language", "ru"))
                .andExpect(status().isOk())
                .andExpect(content().string("ru"));

        // Second request with "de"
        mockMvc.perform(get("/test/locale")
                        .header("foremen-language", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string("de"));

        // Third request without header — should use default, not leaked "de"
        mockMvc.perform(get("/test/locale"))
                .andExpect(status().isOk())
                .andExpect(content().string(defaultLanguage));
    }
}
