package com.foremen.service.permission.property;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.config.security.PermissionProperties;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.permission.PermissionCache;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property tests for the evaluation logic of {@link ForemenPermissionEvaluator} (task 3.2).
 *
 * <p>The evaluator is exercised against generated {@link RoleEntity} graphs served through a
 * Mockito-stubbed {@link RoleDao}; a fresh {@link PermissionCache} is created per invocation so
 * cache state never leaks between property runs and the load path is exercised directly.</p>
 *
 * <p>Covers the four design properties assigned to this task:</p>
 * <ul>
 *   <li><b>Property 2: Permission set derivation from the role graph</b> &mdash; Validates Requirements 1.2</li>
 *   <li><b>Property 3: An empty permission set denies everything</b> &mdash; Validates Requirements 2.3</li>
 *   <li><b>Property 4: ADMIN is always allowed</b> &mdash; Validates Requirements 3.1, 3.2</li>
 *   <li><b>Property 5: ADMIN bypass requires an exact code match</b> &mdash; Validates Requirements 3.3</li>
 * </ul>
 * plus the unknown-role deny edge case (Requirement 1.3).
 */
class PermissionEvaluatorPropertyTest {

    private static final String ADMIN = ForemenPermissionEvaluator.ADMIN_ROLE_CODE;

    // ------------------------------------------------------------------
    // Property 2: Permission set derivation from the role graph
    // ------------------------------------------------------------------

    // Feature: FOR-03-03-permission-evaluator, Property 2: Permission set derivation from the role graph.
    // For any generated RoleEntity whose roleResources reference resource codes and operation codes, the load path
    // produces exactly the flattened set of (resourceCode, operationCode) pairs — no more, no fewer. The evaluator
    // therefore allows exactly those pairs and denies everything else.
    /**
     * <b>Validates: Requirements 1.2</b>
     */
    @Property(tries = 100)
    void permissionSetDerivedExactlyFromRoleGraph(
            @ForAll("nonAdminCodes") String roleCode,
            @ForAll("resourceGrantMaps") Map<String, Set<String>> matrix,
            @ForAll("codes") String queryResource,
            @ForAll("codes") String queryOperation) {

        RoleEntity role = buildRole(roleCode, matrix);
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of(roleCode, role));

        // Expected: the flattened set of (resource, operation) pairs the graph grants.
        boolean expected = matrix.getOrDefault(queryResource, Set.of()).contains(queryOperation);

        assertThat(evaluator.isAllowed(roleCode, queryResource, queryOperation))
                .as("Role '%s' with matrix %s: allow(%s,%s) must equal exact derived membership",
                        roleCode, matrix, queryResource, queryOperation)
                .isEqualTo(expected);
    }

    // Feature: FOR-03-03-permission-evaluator, Property 2: Permission set derivation from the role graph.
    // Every (resource, operation) pair present in the generated graph is allowed, and the derived set contains
    // no extra pairs (verified by counting the total grants and probing each one).
    /**
     * <b>Validates: Requirements 1.2</b>
     */
    @Property(tries = 100)
    void everyGrantedPairIsAllowedAndNoExtras(
            @ForAll("nonAdminCodes") String roleCode,
            @ForAll("resourceGrantMaps") Map<String, Set<String>> matrix) {

        RoleEntity role = buildRole(roleCode, matrix);
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of(roleCode, role));

        // Direct load-path assertion: the derived permission set matches the flattened graph exactly.
        Set<String> expectedKeys = new HashSet<>();
        matrix.forEach((res, ops) -> ops.forEach(op -> expectedKeys.add(res + ":" + op)));

        Set<String> actualKeys = evaluator.loadPermissionSet(roleCode).grants();

        assertThat(actualKeys)
                .as("Derived permission set for '%s' must equal the flattened graph", roleCode)
                .isEqualTo(expectedKeys);

        // And the decision function agrees with membership for every granted pair.
        matrix.forEach((res, ops) -> ops.forEach(op ->
                assertThat(evaluator.isAllowed(roleCode, res, op))
                        .as("Granted pair (%s,%s) must be allowed", res, op)
                        .isTrue()));
    }

    // ------------------------------------------------------------------
    // Property 3: An empty permission set denies everything
    // ------------------------------------------------------------------

    // Feature: FOR-03-03-permission-evaluator, Property 3: An empty permission set denies everything.
    // For any non-ADMIN role whose matrix is empty (or unknown), any (resource, operation) pair is denied.
    /**
     * <b>Validates: Requirements 2.3</b>
     */
    @Property(tries = 100)
    void emptyPermissionSetDeniesEverything(
            @ForAll("nonAdminCodes") String roleCode,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation) {

        // A role that exists but has no roleResources -> empty permission set.
        RoleEntity role = buildRole(roleCode, Map.of());
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of(roleCode, role));

        assertThat(evaluator.isAllowed(roleCode, resource, operation))
                .as("Empty matrix for '%s' must deny (%s,%s)", roleCode, resource, operation)
                .isFalse();
    }

    // Feature: FOR-03-03-permission-evaluator, Property 3 (edge case, Requirement 1.3): unknown role denies everything.
    // For any non-ADMIN role code that does not resolve to a RoleEntity, any (resource, operation) pair is denied.
    /**
     * <b>Validates: Requirements 1.3, 2.3</b>
     */
    @Property(tries = 100)
    void unknownRoleDeniesEverything(
            @ForAll("nonAdminCodes") String roleCode,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation) {

        // No role registered -> RoleDao.findByCode returns empty -> deny by default.
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of());

        assertThat(evaluator.isAllowed(roleCode, resource, operation))
                .as("Unknown role '%s' must deny (%s,%s)", roleCode, resource, operation)
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Property 4: ADMIN is always allowed
    // ------------------------------------------------------------------

    // Feature: FOR-03-03-permission-evaluator, Property 4: ADMIN is always allowed.
    // For any (resource, operation) pair and any matrix (including one that would otherwise deny — here nothing is
    // registered so a non-ADMIN would be denied), the literal ADMIN code is always allowed without a matrix lookup.
    /**
     * <b>Validates: Requirements 3.1, 3.2</b>
     */
    @Property(tries = 100)
    void adminIsAlwaysAllowed(
            @ForAll("codes") String resource,
            @ForAll("codes") String operation) {

        // No ADMIN role registered in the DAO: if the evaluator consulted the matrix it would deny.
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of());

        assertThat(evaluator.isAllowed(ADMIN, resource, operation))
                .as("ADMIN must be allowed for every (%s,%s) without consulting the matrix", resource, operation)
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Property 5: ADMIN bypass requires an exact code match
    // ------------------------------------------------------------------

    // Feature: FOR-03-03-permission-evaluator, Property 5: ADMIN bypass requires an exact code match.
    // For any near-miss of "ADMIN" (case-differing, whitespace-padded, or with extra characters) evaluated against
    // an empty matrix, the decision is deny; only the exact literal ADMIN is bypassed.
    /**
     * <b>Validates: Requirements 3.3</b>
     */
    @Property(tries = 100)
    void adminBypassRequiresExactCodeMatch(
            @ForAll("adminNearMisses") String nearMiss,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation) {

        Assume.that(!ADMIN.equals(nearMiss));

        // The near-miss code either does not resolve to a role, or resolves to an empty-matrix role;
        // build an empty-matrix role for it so we test that the matrix (empty) governs, not the bypass.
        RoleEntity role = buildRole(nearMiss, Map.of());
        ForemenPermissionEvaluator evaluator = evaluatorFor(Map.of(nearMiss, role));

        assertThat(evaluator.isAllowed(nearMiss, resource, operation))
                .as("Near-miss admin code '%s' must NOT be bypassed", nearMiss)
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds an evaluator over a Mockito-stubbed {@link RoleDao} that resolves the supplied roles by code
     * (and returns empty for anything else), with a fresh {@link PermissionCache} so no state leaks.
     */
    private ForemenPermissionEvaluator evaluatorFor(Map<String, RoleEntity> rolesByCode) {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.findByCode(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rolesByCode.get(inv.getArgument(0, String.class))));
        PermissionCache cache = new PermissionCache(new PermissionProperties(null));
        return new ForemenPermissionEvaluator(roleDao, cache);
    }

    /**
     * Assembles a {@link RoleEntity} graph from a resource-code -> operation-codes matrix using the
     * entity setters (entities are Lombok {@code @Setter} + {@code @NoArgsConstructor}).
     */
    private RoleEntity buildRole(String code, Map<String, Set<String>> matrix) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        List<RoleResourceEntity> roleResources = new ArrayList<>();
        matrix.forEach((resourceCode, operationCodes) -> {
            ResourceEntity resource = new ResourceEntity();
            resource.setCode(resourceCode);

            RoleResourceEntity rr = new RoleResourceEntity();
            rr.setRole(role);
            rr.setResource(resource);

            List<OperationEntity> operations = new ArrayList<>();
            for (String opCode : operationCodes) {
                OperationEntity op = new OperationEntity();
                op.setCode(opCode);
                operations.add(op);
            }
            rr.setOperations(operations);
            roleResources.add(rr);
        });
        role.setRoleResources(roleResources);
        return role;
    }

    // ------------------------------------------------------------------
    // Arbitrary providers
    // ------------------------------------------------------------------

    /** Resource/operation codes: non-blank alphabetic strings, mimicking real codes like PROJECTS, CREATE. */
    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
    }

    /** Role codes that are guaranteed not to equal the literal ADMIN. */
    @Provide
    Arbitrary<String> nonAdminCodes() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12)
                .filter(s -> !ADMIN.equals(s));
    }

    /**
     * A matrix from distinct resource codes to a (possibly empty) set of operation codes.
     * Using a map keeps resource codes distinct, matching the role_resources unique constraint per role.
     */
    @Provide
    Arbitrary<Map<String, Set<String>>> resourceGrantMaps() {
        Arbitrary<Set<String>> ops = codes().set().ofMinSize(0).ofMaxSize(4);
        return Arbitraries.maps(codes(), ops).ofMinSize(0).ofMaxSize(6);
    }

    /**
     * Near-misses of the literal {@code ADMIN}: case variants, whitespace-padded, and extra-character forms.
     * The property additionally assumes the value is not exactly ADMIN.
     */
    @Provide
    Arbitrary<String> adminNearMisses() {
        Arbitrary<String> caseVariants = Arbitraries.of("admin", "Admin", "aDMIN", "AdMiN", "ADMiN");
        Arbitrary<String> whitespacePadded = Arbitraries.of(" ADMIN", "ADMIN ", " ADMIN ", "\tADMIN", "ADMIN\n");
        Arbitrary<String> extraChars = Arbitraries.of("ADMINN", "XADMIN", "ADMIN_", "ADMIN1", "SUPERADMIN", "ADMINS");
        Arbitrary<String> arbitrary = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        return Arbitraries.oneOf(caseVariants, whitespacePadded, extraChars, arbitrary);
    }
}
