package com.foremen.config.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JpaAuditingConfigTest {

    private final JpaAuditingConfig config = new JpaAuditingConfig();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("JpaAuditingConfig has @Configuration annotation")
    void classHasConfigurationAnnotation() {
        assertTrue(JpaAuditingConfig.class.isAnnotationPresent(Configuration.class));
    }

    @Test
    @DisplayName("JpaAuditingConfig has @EnableJpaAuditing annotation")
    void classHasEnableJpaAuditingAnnotation() {
        assertTrue(JpaAuditingConfig.class.isAnnotationPresent(EnableJpaAuditing.class));
    }

    @Test
    @DisplayName("auditorAware returns 'system' when authentication is null")
    void returnsSystemWhenAuthenticationIsNull() {
        SecurityContextHolder.getContext().setAuthentication(null);

        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
    }

    @Test
    @DisplayName("auditorAware returns 'system' when authentication is anonymous")
    void returnsSystemWhenAuthenticationIsAnonymous() {
        AnonymousAuthenticationToken anonymousToken = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        SecurityContextHolder.getContext().setAuthentication(anonymousToken);

        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
    }

    @Test
    @DisplayName("auditorAware returns username when authentication is valid and authenticated")
    void returnsUsernameWhenAuthenticated() {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                "john.doe", "password", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);

        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        assertTrue(auditor.isPresent());
        assertEquals("john.doe", auditor.get());
    }
}
