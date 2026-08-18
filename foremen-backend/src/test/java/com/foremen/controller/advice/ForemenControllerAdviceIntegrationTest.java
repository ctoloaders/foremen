package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(ForemenControllerAdviceIntegrationTest.TestWebConfig.class)
class ForemenControllerAdviceIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Configuration
    @EnableWebMvc
    @Import({ForemenControllerAdvice.class, ExceptionTestController.class})
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
    @Validated
    static class ExceptionTestController {

        @GetMapping("/api-exception")
        public void throwApiException() {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.not.found");
        }

        @PostMapping("/validation")
        public void validateDto(@RequestBody @jakarta.validation.Valid TestDto dto) {
            // validation triggers automatically
        }

        @GetMapping("/runtime")
        public void throwRuntime() {
            throw new RuntimeException("internal details that should not leak");
        }
    }

    record TestDto(
            @NotBlank String name,
            @NotBlank String email
    ) {}

    @Test
    @DisplayName("ForemenApiException returns correct JSON structure with status, error, message, path, timestamp")
    void foremenApiExceptionReturnsCorrectJsonStructure() throws Exception {
        mockMvc.perform(get("/test/api-exception"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/test/api-exception"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Accept-Language: ru returns Russian localized message")
    void localeSwitchingWithAcceptLanguageRu() throws Exception {
        mockMvc.perform(get("/test/runtime")
                        .header("Accept-Language", "ru"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Произошла внутренняя ошибка. Попробуйте позже."));
    }

    @Test
    @DisplayName("Bean validation error returns 400 with populated fieldErrors map")
    void beanValidationReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    @DisplayName("Default locale (no Accept-Language) returns Polish message")
    void defaultLocaleReturnPolishMessage() throws Exception {
        mockMvc.perform(get("/test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Wystąpił błąd wewnętrzny. Spróbuj ponownie później."));
    }
}
