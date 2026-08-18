package com.foremen.config.audit;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 4: Auditor Provider Returns Authenticated Principal
 *
 * For any non-null, non-anonymous Authentication object in the SecurityContext with a principal name,
 * the AuditorAware bean SHALL return that principal name. For any null, unauthenticated, or anonymous
 * authentication state, it SHALL return "system".
 *
 * Validates: Requirements 5.4, 5.5
 */
class AuditorAwarePropertyTest {

    private final JpaAuditingConfig config = new JpaAuditingConfig();

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Property(tries = 100)
    void auditorReturnsAuthenticatedPrincipalName(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String username) {
        // Arrange: set up authenticated security context with generated username
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                username, "password", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);

        // Act
        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        // Assert: auditor returns the exact username
        assertTrue(auditor.isPresent());
        assertEquals(username, auditor.get());
    }

    @Property(tries = 100)
    void auditorReturnsSystemForNullAuthentication() {
        // Arrange: no authentication set (null)
        SecurityContextHolder.getContext().setAuthentication(null);

        // Act
        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        // Assert: fallback to "system"
        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
    }

    @Property(tries = 100)
    void auditorReturnsSystemForAnonymousAuthentication(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String principalName) {
        // Arrange: anonymous authentication token
        AnonymousAuthenticationToken anonymousToken = new AnonymousAuthenticationToken(
                "key", principalName, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        SecurityContextHolder.getContext().setAuthentication(anonymousToken);

        // Act
        AuditorAware<String> auditorAware = config.auditorAware();
        Optional<String> auditor = auditorAware.getCurrentAuditor();

        // Assert: even with a principal name, anonymous tokens should return "system"
        assertTrue(auditor.isPresent());
        assertEquals("system", auditor.get());
    }
}
