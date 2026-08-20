package com.foremen.config.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

@Component
public class ForemenLocaleInterceptor implements HandlerInterceptor {

    public static final String LOCALE_HEADER = "foremen-language";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String language = request.getHeader(LOCALE_HEADER);
        if (language != null && !language.isBlank()) {
            Locale locale = Locale.forLanguageTag(language.trim());
            LocaleContextHolder.setLocale(locale);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LocaleContextHolder.resetLocaleContext();
    }
}
