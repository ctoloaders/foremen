package com.foremen.service;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Example/unit tests for {@link ProjectScopedService#assertProjectAccess(Object)}'s decision logic —
 * one explicit case per decision branch (task 2.2).
 *
 * <p>Each denial branch asserts the thrown {@link ForemenApiException} carries HTTP status 404 and
 * message code {@code error.entity.not.found} (the {@code Access_Denied_Outcome}), so an out-of-scope
 * entity is indistinguishable from a missing one. The ADMIN-bypass case additionally asserts that the
 * owning-project resolver {@code getProjectId} is never consulted.</p>
 *
 * <p>Branches covered:</p>
 * <ul>
 *     <li>no authenticated caller &rarr; deny (Requirement 2.3).</li>
 *     <li>blank/non-numeric principal name &rarr; deny (Requirement 2.4).</li>
 *     <li>ADMIN authority &rarr; allow, {@code getProjectId} NOT called (Requirement 2.2, 2.9).</li>
 *     <li>non-ADMIN with an empty allowed set &rarr; deny (Requirement 2.5).</li>
 *     <li>non-ADMIN, owning id in the allowed set &rarr; allow (Requirement 2.6).</li>
 *     <li>non-ADMIN, owning id NOT in the allowed set &rarr; deny (Requirement 2.7).</li>
 *     <li>non-ADMIN, {@code null} owning id &rarr; deny (Requirement 2.8).</li>
 * </ul>
 *
 * <p>The interface's pure decision is exercised through {@link StubScopedService}, a minimal
 * implementor that supplies no-op CRUD plumbing (needed only so it can be instantiated, since the
 * interface now extends {@link AdminService}), a fixed allowed set, and a stubbed {@code getProjectId}
 * that records whether it was invoked.</p>
 */
// Feature: FOR-03-04a-project-actions-validation, example tests for assertProjectAccess branches
class ProjectScopedServiceDecisionExampleTest {

    private static final Long ENTITY_ID = 42L;
    private static final String ROLE_ADMIN_AUTHORITY = "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE;
    private static final String NON_ADMIN_AUTHORITY = "ROLE_MANAGER";
    private static final String EXPECTED_MESSAGE_CODE = "error.entity.not.found";

    @BeforeEach
    void clearBefore() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAfter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no authenticated caller -> deny with 404 error.entity.not.found (Req 2.3)")
    void noAuthenticatedCaller_denies() {
        // No authentication set in the SecurityContext.
        StubScopedService service = new StubScopedService(Set.of(7L), 7L);

        assertDenied(() -> service.assertProjectAccess(ENTITY_ID));
    }

    @Test
    @DisplayName("non-numeric principal name -> deny with 404 error.entity.not.found (Req 2.4)")
    void nonNumericPrincipal_denies() {
        authenticateWith("admin@foremen.com", NON_ADMIN_AUTHORITY);
        StubScopedService service = new StubScopedService(Set.of(7L), 7L);

        assertDenied(() -> service.assertProjectAccess(ENTITY_ID));
    }

    @Test
    @DisplayName("ADMIN authority -> allow, and getProjectId is never called (Req 2.2, 2.9)")
    void adminCaller_allowsWithoutResolvingOwningProject() {
        authenticateWith("100", ROLE_ADMIN_AUTHORITY);
        // Even with an empty allowed set, ADMIN bypasses the ownership check entirely.
        StubScopedService service = new StubScopedService(Set.of(), 7L);

        assertThatCode(() -> service.assertProjectAccess(ENTITY_ID))
                .as("an ADMIN caller must be allowed regardless of ownership")
                .doesNotThrowAnyException();
        assertThat(service.getProjectIdCalled)
                .as("ADMIN bypass must NOT resolve the owning project id (getProjectId not called)")
                .isFalse();
    }

    @Test
    @DisplayName("non-ADMIN with empty allowed set -> deny with 404 error.entity.not.found (Req 2.5)")
    void nonAdminEmptyAllowedSet_denies() {
        authenticateWith("100", NON_ADMIN_AUTHORITY);
        StubScopedService service = new StubScopedService(Set.of(), 7L);

        assertDenied(() -> service.assertProjectAccess(ENTITY_ID));
    }

    @Test
    @DisplayName("non-ADMIN, owning id in allowed set -> allow (Req 2.6)")
    void nonAdminOwningIdInAllowedSet_allows() {
        authenticateWith("100", NON_ADMIN_AUTHORITY);
        StubScopedService service = new StubScopedService(Set.of(7L, 8L), 7L);

        assertThatCode(() -> service.assertProjectAccess(ENTITY_ID))
                .as("a non-ADMIN caller whose owning project id is in the allowed set must be allowed")
                .doesNotThrowAnyException();
        assertThat(service.getProjectIdCalled)
                .as("the owning project id must be resolved for a non-ADMIN caller")
                .isTrue();
    }

    @Test
    @DisplayName("non-ADMIN, owning id NOT in allowed set -> deny with 404 error.entity.not.found (Req 2.7)")
    void nonAdminOwningIdOutOfAllowedSet_denies() {
        authenticateWith("100", NON_ADMIN_AUTHORITY);
        StubScopedService service = new StubScopedService(Set.of(7L, 8L), 99L);

        assertDenied(() -> service.assertProjectAccess(ENTITY_ID));
    }

    @Test
    @DisplayName("non-ADMIN, null owning id -> deny with 404 error.entity.not.found (Req 2.8)")
    void nonAdminNullOwningId_denies() {
        authenticateWith("100", NON_ADMIN_AUTHORITY);
        StubScopedService service = new StubScopedService(Set.of(7L, 8L), null);

        assertDenied(() -> service.assertProjectAccess(ENTITY_ID));
    }

    // --- assertions ---

    /**
     * Asserts the runnable throws the {@code Access_Denied_Outcome}: a {@link ForemenApiException}
     * with HTTP status 404 and message code {@code error.entity.not.found}.
     */
    private void assertDenied(ThrowingCallable action) {
        assertThatThrownBy(action::call)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(thrown -> {
                    ForemenApiException ex = (ForemenApiException) thrown;
                    assertThat(ex.getStatus())
                            .as("denial must carry HTTP status 404")
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageCode())
                            .as("denial must carry message code '%s'", EXPECTED_MESSAGE_CODE)
                            .isEqualTo(EXPECTED_MESSAGE_CODE);
                });
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }

    // --- SecurityContext helpers ---

    private void authenticateWith(String principalName, String authority) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // --- Stub service ---

    /**
     * A minimal {@link ProjectScopedService} stub that exercises only the interface's default
     * {@code assertProjectAccess} decision. It returns a fixed allowed set from
     * {@code allowedProjectIds(userId)} and a fixed owning project id from {@code getProjectId(id)},
     * recording whether {@code getProjectId} was invoked so the ADMIN-bypass case can assert it was
     * not consulted. All CRUD plumbing is no-op (unused by the decision logic).
     */
    static class StubScopedService implements ProjectScopedService<Object, Object, Object, Object> {

        private final Set<Long> allowed;
        private final Long owningProjectId;
        boolean getProjectIdCalled = false;

        StubScopedService(Set<Long> allowed, Long owningProjectId) {
            this.allowed = allowed;
            this.owningProjectId = owningProjectId;
        }

        @Override
        public Long getProjectId(Object entityId) {
            getProjectIdCalled = true;
            return owningProjectId;
        }

        @Override
        public String getProjectIdPath() {
            return "projectId";
        }

        @Override
        public Set<Long> allowedProjectIds(Long userId) {
            return allowed;
        }

        // --- no-op CRUD plumbing inherited from AdminService (unused by assertProjectAccess) ---

        @Override
        public com.foremen.mapper.ServiceToDaoMapper<Object, Object, Object> getMapper() {
            return null;
        }

        @Override
        public com.foremen.dao.AdminDao<Object, Object> getDao() {
            return null;
        }

        @Override
        public com.foremen.service.audit.AuditLogDao getAuditLogDao() {
            return null;
        }

        @Override
        public jakarta.persistence.EntityManager getEntityManager() {
            return null;
        }

        @Override
        public Class<Object> getDaoModelClass() {
            return Object.class;
        }
    }
}
