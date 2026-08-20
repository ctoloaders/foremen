package com.foremen.config.web;

import com.foremen.config.i18n.ForemenLocaleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ForemenWebMvcConfig implements WebMvcConfigurer {

    private final ForemenLocaleInterceptor localeInterceptor;

    public ForemenWebMvcConfig(ForemenLocaleInterceptor localeInterceptor) {
        this.localeInterceptor = localeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeInterceptor)
                .addPathPatterns("/**");
    }
}
