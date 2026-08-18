package com.foremen.config.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

@Component
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves message code to localized string.
     * Falls back to the code itself if not found in the bundle.
     */
    public String resolve(String code, Object[] params, Locale locale) {
        return messageSource.getMessage(code, params, code, locale);
    }

    @Configuration
    static class MessageSourceConfig {

        @Bean
        public ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setUseCodeAsDefaultMessage(true);
            return source;
        }

        @Bean
        public LocaleResolver localeResolver() {
            AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
            resolver.setDefaultLocale(Locale.of("pl"));
            return resolver;
        }
    }
}
