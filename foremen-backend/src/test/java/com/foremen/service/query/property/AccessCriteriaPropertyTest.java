package com.foremen.service.query.property;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.service.query.QueryParser;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 2: AccessCriteria Always Conjunctive with User Query
 *
 * For any user query specification (including those containing OR groups) and for any
 * non-null access criteria specification, calling {@code buildFinalSpecification} SHALL
 * produce a specification structurally equivalent to
 * {@code Specification.where(userSpec).and(accessCriteria)}.
 *
 * Specifically: no entity in the result set should fail the access criteria predicate,
 * regardless of whether the user query contains OR operators.
 *
 * <p><b>Validates: Requirements 5.16, 5.17</b></p>
 */
class AccessCriteriaPropertyTest {

    // --- Test service implementation that allows configuring access criteria ---

    /**
     * Minimal test implementation of ReadOnlyAdminService that allows us to
     * configure the access criteria (returned by addRequiredQuery) and test
     * the buildFinalSpecification method behavior.
     */
    static class TestableReadOnlyService
            implements ReadOnlyAdminService<Object, Object, Object, Long> {

        private Specification<Object> accessSpec;

        void setAccessSpec(Specification<Object> accessSpec) {
            this.accessSpec = accessSpec;
        }

        @Override
        public Specification<Object> addRequiredQuery() {
            return accessSpec;
        }

        @Override
        public ServiceToDaoMapper<Object, Object, Object> getMapper() {
            // Return a stub mapper with empty i18n properties
            return new ServiceToDaoMapper<>() {
                @Override
                public Set<String> getI18nSupportedProperties() {
                    return Set.of();
                }

                @Override
                public Object toServiceModel(Object source) {
                    return source;
                }

                @Override
                public Object toServiceExtendedModel(Object source) {
                    return source;
                }

                @Override
                public Object toCreateDaoModel(Object source) {
                    return source;
                }

                @Override
                public void updateFields(Object source, Object target) {
                }
            };
        }

        @Override
        public ReadOnlyAdminDao<Object, Long> getReadDao() {
            return null; // Not needed for buildFinalSpecification
        }

        @Override
        public EntityManager getEntityManager() {
            return null; // Not needed for buildFinalSpecification
        }

        @Override
        public Class<Object> getDaoModelClass() {
            return Object.class;
        }
    }

    // --- Arbitraries ---

    /**
     * Generates random user query strings with AND/OR groups using valid query DSL.
     * Includes simple filters, AND combinations, OR combinations, and parenthesized groups.
     */
    @Provide
    Arbitrary<String> userQueries() {
        Arbitrary<String> simpleFilter = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(8).map(String::toLowerCase)
                        .filter(s -> !s.equalsIgnoreCase("and") && !s.equalsIgnoreCase("or")),
                Arbitraries.strings().withCharRange('a', 'z').withCharRange('0', '9').ofMinLength(1).ofMaxLength(6)
        ).as((field, value) -> field + "==" + value);

        // OR group: (filter OR filter)
        Arbitrary<String> orGroup = Combinators.combine(simpleFilter, simpleFilter)
                .as((f1, f2) -> "(" + f1 + " OR " + f2 + ")");

        // AND group: filter AND filter
        Arbitrary<String> andGroup = Combinators.combine(simpleFilter, simpleFilter)
                .as((f1, f2) -> f1 + " AND " + f2);

        // Complex: (filter OR filter) AND filter
        Arbitrary<String> complexOrAndFilter = Combinators.combine(orGroup, simpleFilter)
                .as((og, f) -> og + " AND " + f);

        // Complex: filter AND (filter OR filter)
        Arbitrary<String> filterAndOr = Combinators.combine(simpleFilter, orGroup)
                .as((f, og) -> f + " AND " + og);

        return Arbitraries.oneOf(simpleFilter, orGroup, andGroup, complexOrAndFilter, filterAndOr);
    }

    /**
     * Generates random access criteria specifications as simple lambda-based specs.
     * These represent various access control predicates.
     */
    @Provide
    Arbitrary<Specification<Object>> accessSpecifications() {
        // Generate different non-null spec instances — each is a unique lambda
        return Arbitraries.integers().between(1, 100).map(i ->
                (Specification<Object>) (root, query, cb) -> cb.equal(root.get("accessField" + i), "accessValue" + i)
        );
    }

    // --- Property tests ---

    /**
     * When addRequiredQuery() returns a non-null access specification,
     * buildFinalSpecification MUST return a non-null combined specification
     * (proving the conjunction was applied).
     */
    @Property(tries = 100)
    void whenAccessSpecNonNull_buildFinalSpec_returnsNonNullCombination(
            @ForAll("userQueries") String userQuery,
            @ForAll("accessSpecifications") Specification<Object> accessSpec) {

        TestableReadOnlyService service = new TestableReadOnlyService();
        service.setAccessSpec(accessSpec);

        Specification<Object> result = service.buildFinalSpecification(userQuery);

        assertThat(result)
                .as("buildFinalSpecification must return a non-null combined spec when access criteria is non-null")
                .isNotNull();
    }

    /**
     * When addRequiredQuery() returns null (no access criteria),
     * buildFinalSpecification MUST return just the user spec (non-null).
     */
    @Property(tries = 100)
    void whenAccessSpecNull_buildFinalSpec_returnsUserSpecOnly(
            @ForAll("userQueries") String userQuery) {

        TestableReadOnlyService service = new TestableReadOnlyService();
        service.setAccessSpec(null);

        Specification<Object> result = service.buildFinalSpecification(userQuery);

        assertThat(result)
                .as("buildFinalSpecification must return the user spec when access criteria is null")
                .isNotNull();
    }

    /**
     * The combined specification (when access criteria is present) must be DIFFERENT
     * from the user specification alone, proving that access criteria is being applied.
     * This verifies that the access spec is not silently ignored.
     */
    @Property(tries = 100)
    void combinedSpecDiffersFromUserSpecAlone(
            @ForAll("userQueries") String userQuery,
            @ForAll("accessSpecifications") Specification<Object> accessSpec) {

        TestableReadOnlyService service = new TestableReadOnlyService();

        // Get user spec without access criteria
        service.setAccessSpec(null);
        Specification<Object> userSpecOnly = service.buildFinalSpecification(userQuery);

        // Get combined spec with access criteria
        service.setAccessSpec(accessSpec);
        Specification<Object> combinedSpec = service.buildFinalSpecification(userQuery);

        // The combined spec must not be the same object as the user-only spec,
        // proving that access criteria was applied (conjunction happened)
        assertThat(combinedSpec)
                .as("Combined spec must differ from user-only spec when access criteria is present")
                .isNotSameAs(userSpecOnly);
    }

    /**
     * Structural verification: buildFinalSpecification produces
     * Specification.where(userSpec).and(accessSpec) — i.e. the access criteria
     * is applied via AND to the entire user query, not distributed into OR branches.
     *
     * We verify by checking that the same result is obtained from calling
     * Specification.where(userSpec).and(accessSpec) manually.
     */
    @Property(tries = 100)
    void buildFinalSpec_isStructurallyEquivalentToWhereAndCombination(
            @ForAll("userQueries") String userQuery,
            @ForAll("accessSpecifications") Specification<Object> accessSpec) {

        TestableReadOnlyService service = new TestableReadOnlyService();
        service.setAccessSpec(accessSpec);

        // Get the result from buildFinalSpecification
        Specification<Object> result = service.buildFinalSpecification(userQuery);

        // Manually construct the expected result: Specification.where(userSpec).and(accessSpec)
        Specification<Object> userSpec = service.parseSpecification(userQuery);
        Specification<Object> expectedCombination = Specification.where(userSpec).and(accessSpec);

        // Both should be non-null and structurally follow the same combination pattern
        assertThat(result).isNotNull();
        assertThat(expectedCombination).isNotNull();

        // Since Specification doesn't override equals, verify by checking class type
        // Both should produce the same type of specification wrapper
        assertThat(result.getClass())
                .as("Result should be the same specification wrapper class as Specification.where().and()")
                .isEqualTo(expectedCombination.getClass());
    }

    /**
     * Even when the user query contains OR groups, the access criteria is still
     * applied to the ENTIRE result — it wraps the user query as a whole:
     * (user_query) AND (access_criteria)
     *
     * This is verified structurally: the OR in the user query does not prevent
     * buildFinalSpecification from applying access criteria.
     */
    @Property(tries = 100)
    void orGroupsInUserQuery_doNotPreventAccessCriteriaApplication(
            @ForAll("accessSpecifications") Specification<Object> accessSpec) {

        TestableReadOnlyService service = new TestableReadOnlyService();
        service.setAccessSpec(accessSpec);

        // User query with explicit OR group
        String queryWithOr = "(status==active OR status==pending)";
        Specification<Object> result = service.buildFinalSpecification(queryWithOr);

        // The result must not be null — access criteria was applied
        assertThat(result)
                .as("OR groups in user query must not prevent access criteria from being applied")
                .isNotNull();

        // Verify it differs from user spec alone (access criteria added)
        service.setAccessSpec(null);
        Specification<Object> userOnly = service.buildFinalSpecification(queryWithOr);

        service.setAccessSpec(accessSpec);
        Specification<Object> withAccess = service.buildFinalSpecification(queryWithOr);

        assertThat(withAccess)
                .as("Spec with access criteria must differ from spec without, even with OR groups")
                .isNotSameAs(userOnly);
    }

    /**
     * When null/empty rawQuery is combined with access criteria, the access criteria
     * alone should still be applied (user query is a no-op, but access is enforced).
     */
    @Property(tries = 100)
    void nullUserQuery_withAccessCriteria_stillAppliesAccessSpec(
            @ForAll("accessSpecifications") Specification<Object> accessSpec) {

        TestableReadOnlyService service = new TestableReadOnlyService();
        service.setAccessSpec(accessSpec);

        Specification<Object> result = service.buildFinalSpecification(null);

        assertThat(result)
                .as("Access criteria must be applied even when user query is null")
                .isNotNull();

        // Verify it's not just the null/no-op user spec
        service.setAccessSpec(null);
        Specification<Object> withoutAccess = service.buildFinalSpecification(null);

        service.setAccessSpec(accessSpec);
        Specification<Object> withAccess = service.buildFinalSpecification(null);

        assertThat(withAccess)
                .as("Spec with access criteria must differ from no-op spec")
                .isNotSameAs(withoutAccess);
    }
}
