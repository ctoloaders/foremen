package com.foremen.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link PermissionInterceptor} into the Spring MVC interceptor chain so that
 * {@link RequiresPermission}-annotated controller endpoints are enforced before the controller
 * method executes (Requirements 5.1, 5.2).
 *
 * <p>Also enables binding of {@link PermissionProperties} (the {@code foremen.permission}
 * namespace carrying the permission-cache idle TTL) so the dedicated
 * {@link com.foremen.service.permission.PermissionCache} can be configured.
 *
 * <p>This is a dedicated {@link WebMvcConfigurer} distinct from the locale/cors configurers;
 * Spring merges every {@code WebMvcConfigurer} bean, so registering here does not conflict with
 * the existing interceptors.
 */
@Configuration
@EnableConfigurationProperties(PermissionProperties.class)
@RequiredArgsConstructor
public class PermissionInterceptorConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor);
    }
}
