package com.foremen.service.property;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Property test for {@link ProjectScopedService#assertProjectAccess(Object)}'s pure decision logic.
 *
 * <p>Covers the design property assigned to task 2.1:</p>
 * <ul>
 *   <li><b>Property 1: assertProjectAccess decision matches the caller's access state and the
 *       entity's owning project</b> &mdash; Validates Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.7,
 *       2.8, 2.9.</li>
 * </ul>
 *
 * <p>The decision is driven against a minimal {@link ProjectScopedService} stub (supplying no-op
 * CRUD plumbing so it can be instantiated, since the interface now {@code extends AdminService})
 * whose {@code allowedProjectIds(userId)} returns a generated set and whose {@code getProjectId}
 * is a spy returning a generated in-set / out-of-set / {@code null} owning project id (and recording
 * whether it was ever consulted). It is exercised over generated {@code SecurityContext} states:
 * (i) no principal, (ii) non-numeric principal, (iii) ADMIN authority, (iv) non-ADMIN empty allowed
 * set, and (v) non-ADMIN non-empty allowed set.</p>
 *
 * <p>The universal property asserted across all iterations: {@code assertProjectAccess(id)} returns
 * normally IFF the caller is ADMIN, OR the caller is a non-ADMIN with a non-empty allowed set whose
 * resolved owning project id is non-null and contained in that set; in every other case it throws a
 * {@link ForemenApiException} with HTTP status 404 and message code {@code error.entity.not.found}.
 * Additionally, {@code getProjectId} is NEVER invoked on the ADMIN path.</p>
 */
// Feature: FOR-03-04a-project-actions-validation, Property 1: assertProjectAccess decision matches the caller's access state and the entity's owning project
class AssertProjectAccessDecisionPropertyTest {

    private static final String ROLE_ADMIN_AUTHORITY = "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE;
    private static final String BARE_ADMIN_AUTHORITY = ForemenPermissionEvaluator.ADMIN_ROLE_CODE;

    /** How the SecurityContext is populated for a given iteration. */
    private enum CallerKind {
        NO_PRINCIPAL,      // (i)   no authentication in the context
        NON_NUMERIC,       // (ii)  authenticated, but principal name is not a numeric userId
        ADMIN,             // (iii) authenticated with an ADMIN authority
        NON_ADMIN_EMPTY,   // (iv)  authenticated non-ADMIN whose allowed set is empty
        NON_ADMIN_NON_EMPTY// (v)   authenticated non-ADMIN whose allowed set is non-empty
    }

    /** What the spied getProjectId returns for the targeted entity id. */
    private enum OwningIdKind {
        IN_SET,     // owning id is a member of the allowed set
        OUT_OF_SET, // owning id is NOT a member of the allowed set
        NULL        // no such entity -> null owning id
    }

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Property 1 (Requirements 2.2-2.9): across all generated caller states, owning-id outcomes, and
     * entity ids, {@code assertProjectAccess} allows exactly the ADMIN case and the
     * non-ADMIN-non-empty-with-in-set-non-null case, and denies everything else with 404
     * {@code error.entity.not.found}; and {@code getProjectId} is never consulted for an ADMIN.
     */
    @Property(tries = 100)
    void assertProjectAccessDecisionMatchesCallerAndOwningProject(
            @ForAll CallerKind callerKind,
            @ForAll OwningIdKind owningIdKind,
            @ForAll("adminAuthorities") String adminAuthority,
            @ForAll("nonAdminAuthorities") String nonAdminAuthority,
            @ForAll("numericPrincipals") String numericPrincipal,
            @ForAll("nonNumericPrincipals") String nonNumericPrincipal,
            @ForAll("projectIdSets") Set<Long> allowedBase,
            @ForAll("entityIds") Long entityId) {

        // Build the allowed set for this caller kind.
        Set<Long> allowed = switch (callerKind) {
            case NON_ADMIN_EMPTY -> Set.of();
            case NON_ADMIN_NON_EMPTY -> allowedBase.isEmpty() ? Set.of(7L) : allowedBase;
            // For NO_PRINCIPAL / NON_NUMERIC / ADMIN the allowed set is irrelevant to the outcome;
            // use the generated base to prove it is not consulted where it must not be.
            default -> allowedBase;
        };

        // Derive an owning id consistent with the requested OwningIdKind relative to `allowed`.
        Long owningProjectId = switch (owningIdKind) {
            case NULL -> null;
            case IN_SET -> allowed.isEmpty() ? null : allowed.iterator().next();
            case OUT_OF_SET -> outsideOf(allowed);
        };

        SpyStubScopedService service = new SpyStubScopedService(allowed, owningProjectId);

        // Populate the SecurityContext for this caller kind.
        switch (callerKind) {
            case NO_PRINCIPAL -> SecurityContextHolder.clearContext();
            case NON_NUMERIC -> authenticateWith(nonNumericPrincipal, nonAdminAuthority);
            case ADMIN -> authenticateWith(numericPrincipal, adminAuthority);
            case NON_ADMIN_EMPTY, NON_ADMIN_NON_EMPTY -> authenticateWith(numericPrincipal, nonAdminAuthority);
        }

        // The single source of truth for the expected outcome.
        boolean expectAllowed = switch (callerKind) {
            case ADMIN -> true;
            case NON_ADMIN_NON_EMPTY -> owningProjectId != null && allowed.contains(owningProjectId);
            default -> false; // NO_PRINCIPAL, NON_NUMERIC, NON_ADMIN_EMPTY
        };

        if (expectAllowed) {
            service.assertProjectAccess(entityId);
            // returned normally: nothing thrown (Requirements 2.2, 2.6)
        } else {
            ForemenApiException ex = catchThrowableOfType(
                    ForemenApiException.class, () -> service.assertProjectAccess(entityId));
            assertThat(ex)
                    .as("denied caller kind %s / owning %s must throw ForemenApiException",
                            callerKind, owningIdKind)
                    .isNotNull();
            assertThat(ex.getStatus())
                    .as("Access_Denied_Outcome must be HTTP 404 (Requirements 2.3-2.8)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getMessageCode())
                    .as("Access_Denied_Outcome must carry code error.entity.not.found (Requirements 2.7, 2.8)")
                    .isEqualTo("error.entity.not.found");
        }

        // Requirements 2.2, 2.9: an ADMIN caller bypasses ownership -> getProjectId is NEVER consulted.
        if (callerKind == CallerKind.ADMIN) {
            assertThat(service.getProjectIdCalls)
                    .as("ADMIN bypass must not resolve the owning project id (getProjectId not called)")
                    .isZero();
        }
    }

    /** Sanity example: an ADMIN with a null-owning-id target is still allowed and never resolves it. */
    @Property(tries = 100)
    void adminNeverConsultsGetProjectId(
            @ForAll("adminAuthorities") String adminAuthority,
            @ForAll("numericPrincipals") String principal,
            @ForAll("projectIdSets") Set<Long> allowed,
            @ForAll("entityIds") Long entityId) {

        authenticateWith(principal, adminAuthority);
        SpyStubScopedService service = new SpyStubScopedService(allowed, null);

        service.assertProjectAccess(entityId); // allowed, no throw

        assertThat(service.getProjectIdCalls)
                .as("ADMIN bypass must not call getProjectId even when the owning id would be null")
                .isZero();
    }

    // --- helpers ---

    /** Returns a Long guaranteed NOT to be contained in the given set. */
    private static Long outsideOf(Set<Long> allowed) {
        long candidate = 1_000_001L;
        while (allowed.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private void authenticateWith(String principalName, String authority) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // --- stub service ---

    /**
     * A minimal {@link ProjectScopedService} whose {@code allowedProjectIds} returns a fixed set and
     * whose {@code getProjectId} is a spy returning a fixed owning id while counting invocations, so
     * the ADMIN-bypass "never resolves the owning id" guarantee can be verified. It supplies no-op
     * CRUD plumbing (unused by {@code assertProjectAccess}) so it can be instantiated now that
     * {@link ProjectScopedService} {@code extends AdminService}.
     */
    static class SpyStubScopedService implements ProjectScopedService<Object, Object, Object, Object> {

        private final Set<Long> allowed;
        private final Long owningProjectId;
        int getProjectIdCalls = 0;

        SpyStubScopedService(Set<Long> allowed, Long owningProjectId) {
            this.allowed = allowed;
            this.owningProjectId = owningProjectId;
        }

        @Override
        public String getProjectIdPath() {
            return "projectId";
        }

        @Override
        public Set<Long> allowedProjectIds(Long userId) {
            return allowed;
        }

        /** Spy override: records the call and returns the fixed owning id (never hits a real EntityManager). */
        @Override
        public Long getProjectId(Object entityId) {
            getProjectIdCalls++;
            return owningProjectId;
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

    // --- arbitrary providers ---

    /** ADMIN authorities in both the {@code ROLE_ADMIN} and bare {@code ADMIN} forms (Req 2.9). */
    @Provide
    Arbitrary<String> adminAuthorities() {
        return Arbitraries.of(ROLE_ADMIN_AUTHORITY, BARE_ADMIN_AUTHORITY);
    }

    /** Non-ADMIN authorities: {@code ROLE_<code>} for the other system roles plus near-misses. */
    @Provide
    Arbitrary<String> nonAdminAuthorities() {
        return Arbitraries.of(
                "ROLE_MANAGER", "ROLE_FOREMAN", "ROLE_WORKER", "ROLE_FINANCIER", "ROLE_CLIENT",
                "ROLE_ADMINISTRATOR", "ADMINX", "ROLE_ADMINX", "ADMIN_ROLE", "MANAGER");
    }

    /** Numeric principal names (valid userIds). */
    @Provide
    Arbitrary<String> numericPrincipals() {
        return Arbitraries.longs().between(1L, 1_000_000L).map(String::valueOf);
    }

    /** Non-numeric principal names treated as no-auth by {@code parseUserId}. */
    @Provide
    Arbitrary<String> nonNumericPrincipals() {
        return Arbitraries.of(
                "admin@foremen.com", "abc", "12x", "x12", "1.5", " ", "", "not-a-number", "42a");
    }

    /** Possibly-empty allowed project-id sets, kept within a bounded range so OUT_OF_SET is derivable. */
    @Provide
    Arbitrary<Set<Long>> projectIdSets() {
        return Arbitraries.longs().between(1L, 10_000L).set().ofMaxSize(8);
    }

    /** Target entity ids. */
    @Provide
    Arbitrary<Long> entityIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }
}
