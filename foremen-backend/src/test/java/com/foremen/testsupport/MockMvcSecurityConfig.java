package com.foremen.testsupport;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;

/**
 * Test configuration that applies Spring Security's MockMvc integration
 * ({@link SecurityMockMvcConfigurers#springSecurity()}) to the auto-configured {@code MockMvc}.
 *
 * <p>Without this, {@code @WithMockUser} does not populate the request's {@code SecurityContext}
 * for {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} tests, so the migrated
 * {@code anyRequest().authenticated()} filter chain rejects every request with 401. Registering a
 * {@link MockMvcBuilderCustomizer} bean is picked up automatically by Spring Boot's
 * {@code MockMvcAutoConfiguration}, so importing this config wires {@code @WithMockUser} through the
 * real filter chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockMvcSecurityConfig {

    @Bean
    MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
        return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
    }
}
