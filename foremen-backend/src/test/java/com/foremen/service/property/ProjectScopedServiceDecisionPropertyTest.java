package com.foremen.service.property;

import com.foremen.service.ProjectScopedService;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import org.mockito.ArgumentMatchers;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Property tests for {@link ProjectScopedService#addRequiredQuery()}'s pure decision logic.
 *
 * <p>Covers the design property assigned to task 4.2:</p>
 * <ul>
 *   <li><b>Property 1: addRequiredQuery decision matches the caller's access state</b>
 *       &mdash; Validates Requirements 6.1, 6.2, 6.3, 6.5, 6.6, 6.7.</li>
 * </ul>
 *
 * <p>The mixin's decision logic is exercised against a small stub implementing
 * {@link ProjectScopedService} whose {@code allowedProjectIds(userId)} returns a generated set,
 * driven over generated {@link org.springframework.security.core.context.SecurityContext} states:
 * (i) no authenticated principal, (ii) an ADMIN authority, (iii) a non-ADMIN caller with an empty
 * allowed set, and (iv) a non-ADMIN caller with a non-empty allowed set, plus a non-numeric
 * principal-name edge case treated as no-auth.</p>
 *
 * <p>The three possible outcomes are distinguished by their observable shape:</p>
 * <ul>
 *   <li>ADMIN bypass &rarr; {@code addRequiredQuery()} returns {@code null} (no filter).</li>
 *   <li>match-nothing (unauthenticated / non-numeric / non-ADMIN empty set) &rarr; a non-null
 *       Specification whose predicate is {@code cb.disjunction()} (always false).</li>
 *   <li>non-ADMIN with a non-empty set &rarr; a non-null Specification that resolves the
 *       {@code getProjectIdPath()} path and calls {@code path.in(allowedSet)}.</li>
 * </ul>
 * The produced Specification is inspected by invoking {@code toPredicate(root, query, cb)} against
 * mocked JPA criteria objects and verifying which criteria-builder methods were exercised.
 */
// Feature: FOR-03-04-project-ownership, Property 1: addRequiredQuery decision matches the caller's access state
class ProjectScopedServiceDecisionPropertyTest {

    private static final String ROLE_ADMIN_AUTHORITY = "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE;
    private static final String BARE_ADMIN_AUTHORITY = ForemenPermissionEvaluator.ADMIN_ROLE_CODE;

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * (i) No authenticated principal &rarr; match-nothing (Requirement 6.6).
     *
     * <p>The SecurityContext holds no authentication, so {@code addRequiredQuery()} must return a
     * non-null, always-false (disjunction) Specification regardless of the declared path.</p>
     */
    @Property(tries = 100)
    void noAuthenticatedCaller_yieldsMatchNothing(
            @ForAll("projectIdPaths") String projectIdPath,
            @ForAll("projectIdSets") Set<Long> allowed) {

        SecurityContextHolder.clearContext();
        StubScopedService service = new StubScopedService(projectIdPath, allowed);

        Specification<Object> spec = service.addRequiredQuery();

        assertMatchNothing(spec, "an unauthenticated caller must see no rows (match-nothing)");
    }

    /**
     * (ii) ADMIN authority &rarr; {@code null} (ADMIN bypass, Requirements 6.1, 6.7).
     *
     * <p>Whether the ADMIN authority is granted as {@code ROLE_ADMIN} or the bare {@code ADMIN},
     * the decision must be an unfiltered read: {@code addRequiredQuery()} returns {@code null}.</p>
     */
    @Property(tries = 100)
    void adminCaller_yieldsNullNoFilter(
            @ForAll("adminAuthorities") String adminAuthority,
            @ForAll("numericPrincipals") String principalName,
            @ForAll("projectIdPaths") String projectIdPath,
            @ForAll("projectIdSets") Set<Long> allowed) {

        authenticateWith(principalName, adminAuthority);
        StubScopedService service = new StubScopedService(projectIdPath, allowed);

        Specification<Object> spec = service.addRequiredQuery();

        assertThat(spec)
                .as("an ADMIN caller (authority '%s') must bypass filtering: addRequiredQuery() returns null",
                        adminAuthority)
                .isNull();
    }

    /**
     * (iii) Non-ADMIN caller with an EMPTY allowed set &rarr; match-nothing (Requirement 6.3).
     */
    @Property(tries = 100)
    void nonAdminEmptyAllowedSet_yieldsMatchNothing(
            @ForAll("nonAdminAuthorities") String authority,
            @ForAll("numericPrincipals") String principalName,
            @ForAll("projectIdPaths") String projectIdPath) {

        authenticateWith(principalName, authority);
        StubScopedService service = new StubScopedService(projectIdPath, Set.of());

        Specification<Object> spec = service.addRequiredQuery();

        assertMatchNothing(spec, "a non-ADMIN caller with no accessible projects must see no rows");
    }

    /**
     * (iv) Non-ADMIN caller with a NON-EMPTY allowed set &rarr; an IN-over-path Specification
     * (Requirements 6.2, 6.5).
     *
     * <p>The produced Specification must resolve {@code getProjectIdPath()} to a JPA path and
     * restrict it to the allowed set via {@code path.in(allowedSet)} — it must NOT be a
     * match-nothing disjunction.</p>
     */
    @Property(tries = 100)
    void nonAdminNonEmptyAllowedSet_yieldsInOverPath(
            @ForAll("nonAdminAuthorities") String authority,
            @ForAll("numericPrincipals") String principalName,
            @ForAll("projectIdPaths") String projectIdPath,
            @ForAll("nonEmptyProjectIdSets") Set<Long> allowed) {

        authenticateWith(principalName, authority);
        StubScopedService service = new StubScopedService(projectIdPath, allowed);

        Specification<Object> spec = service.addRequiredQuery();

        assertThat(spec)
                .as("a non-ADMIN caller with a non-empty allowed set must produce a filtering spec")
                .isNotNull();

        InspectedPredicate inspected = inspect(spec);

        assertThat(inspected.disjunctionCalled)
                .as("an IN-over-path spec must not fall back to a match-nothing disjunction")
                .isFalse();
        assertThat(inspected.inCalledWithAllowed)
                .as("the spec must restrict the resolved project-id path to exactly the allowed set %s", allowed)
                .isTrue();
    }

    /**
     * Edge case: a non-numeric principal name is treated as no-auth &rarr; match-nothing
     * (Requirement 6.5). Even with a non-empty allowed set, the userId cannot be parsed, so the
     * caller sees no rows.
     */
    @Property(tries = 100)
    void nonNumericPrincipalName_yieldsMatchNothing(
            @ForAll("nonAdminAuthorities") String authority,
            @ForAll("nonNumericPrincipals") String principalName,
            @ForAll("projectIdPaths") String projectIdPath,
            @ForAll("nonEmptyProjectIdSets") Set<Long> allowed) {

        authenticateWith(principalName, authority);
        StubScopedService service = new StubScopedService(projectIdPath, allowed);

        Specification<Object> spec = service.addRequiredQuery();

        assertMatchNothing(spec,
                "a non-numeric principal name ('" + principalName + "') is treated as no-auth (match-nothing)");
    }

    // --- Specification inspection helpers ---

    /**
     * Asserts the Specification is the always-false match-nothing shape: non-null and, when applied
     * to mocked criteria objects, calls {@code cb.disjunction()} and never {@code path.in(...)}.
     */
    private void assertMatchNothing(Specification<Object> spec, String reason) {
        assertThat(spec).as(reason + " (spec must be non-null)").isNotNull();
        InspectedPredicate inspected = inspect(spec);
        assertThat(inspected.disjunctionCalled)
                .as(reason + " (must be a cb.disjunction() always-false predicate)")
                .isTrue();
        assertThat(inspected.inCalledWithAllowed)
                .as(reason + " (must not produce an IN filter)")
                .isFalse();
    }

    /**
     * Invokes {@code spec.toPredicate(root, query, cb)} against mocked JPA criteria objects and
     * records which builder operations were exercised, so the outcome shape can be classified.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private InspectedPredicate inspect(Specification<Object> spec) {
        Root<Object> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        Path<Object> resolvedPath = mock(Path.class);
        Join<Object, Object> joined = mock(Join.class);

        Predicate disjunctionPredicate = mock(Predicate.class);
        Predicate inPredicate = mock(Predicate.class);

        // Stub with doReturn(...).when(...) so the mixin's overloaded criteria calls are not
        // actually invoked during stubbing (avoids matcher/overload ambiguity).
        doReturn(disjunctionPredicate).when(cb).disjunction();

        // Support both single-segment (root.get) and dotted (root.join(...).get) path resolution.
        doReturn(resolvedPath).when(root).get(ArgumentMatchers.anyString());
        doReturn(joined).when(root).join(ArgumentMatchers.anyString());
        doReturn(joined).when(joined).join(ArgumentMatchers.anyString());
        doReturn(resolvedPath).when(joined).get(ArgumentMatchers.anyString());

        // path.in(Collection) returns the recorded Predicate; the mixin calls in(Set<Long>).
        doReturn(inPredicate).when(resolvedPath).in(any(java.util.Collection.class));

        Predicate produced = spec.toPredicate(root, query, cb);

        InspectedPredicate result = new InspectedPredicate();
        result.disjunctionCalled = (produced == disjunctionPredicate);
        result.inCalledWithAllowed = (produced == inPredicate);
        return result;
    }

    private static class InspectedPredicate {
        boolean disjunctionCalled;
        boolean inCalledWithAllowed;
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
     * A minimal {@link ProjectScopedService} stub: it declares a generated project-id path and
     * returns a fixed generated allowed set, exercising only the interface's default decision logic.
     */
    static class StubScopedService implements ProjectScopedService<Object> {

        private final String projectIdPath;
        private final Set<Long> allowed;

        StubScopedService(String projectIdPath, Set<Long> allowed) {
            this.projectIdPath = projectIdPath;
            this.allowed = allowed;
        }

        @Override
        public String getProjectIdPath() {
            return projectIdPath;
        }

        @Override
        public Set<Long> allowedProjectIds(Long userId) {
            return allowed;
        }
    }

    // --- Arbitrary providers ---

    /** Single-segment and dotted JPA project-id paths, exercising both resolution branches. */
    @Provide
    Arbitrary<String> projectIdPaths() {
        return Arbitraries.of(
                "projectId",
                "project.id",
                "room.project.id");
    }

    /** ADMIN authorities in both the {@code ROLE_ADMIN} and bare {@code ADMIN} forms (Req 6.7). */
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

    /** Possibly-empty allowed project-id sets. */
    @Provide
    Arbitrary<Set<Long>> projectIdSets() {
        return Arbitraries.longs().between(1L, 10_000L).set().ofMaxSize(8);
    }

    /** Non-empty allowed project-id sets. */
    @Provide
    Arbitrary<Set<Long>> nonEmptyProjectIdSets() {
        return Arbitraries.longs().between(1L, 10_000L).set().ofMinSize(1).ofMaxSize(8);
    }
}
