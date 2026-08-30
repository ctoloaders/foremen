package com.foremen.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Exposes the application's password encoder as a Spring bean.
 *
 * <p>Passwords are hashed with bcrypt at cost factor 12 (Requirement 10.1), the strength
 * used consistently across login verification, admin bootstrap, and password reset.
 */
@Configuration
public class PasswordEncoderConfig {

    private static final int BCRYPT_COST_FACTOR = 12;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST_FACTOR);
    }
}
