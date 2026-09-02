package com.foremen.service.permission.property;

import com.foremen.service.permission.PermissionSet;
import net.jqwik.api.*;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link PermissionSet}.
 *
 * <p>Covers the two design properties assigned to task 1.4:</p>
 * <ul>
 *   <li><b>Property 1: Decision equals matrix membership (deny by default)</b>
 *       &mdash; Validates Requirements 1.1, 2.1, 2.2</li>
 *   <li><b>Property 6: Grants match resource and operation codes exactly</b>
 *       &mdash; Validates Requirements 1.4</li>
 * </ul>
 */
class PermissionSetPropertyTest {

    // Feature: FOR-03-03-permission-evaluator, Property 1: Decision equals matrix membership (deny by default).
    // For any generated grant set and any (resource, operation) query, allows(...) returns true if and only if
    // the exact "RESOURCE:OPERATION" key is a member of the grants; absent resources and partial operations deny.
    /**
     * <b>Validates: Requirements 1.1, 2.1, 2.2</b>
     */
    @Property(tries = 100)
    void allowsEqualsExactMembership(
            @ForAll("grantSets") Set<String> grants,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation) {

        PermissionSet set = new PermissionSet(grants);

        boolean expected = grants.contains(PermissionSet.key(resource, operation));

        assertThat(set.allows(resource, operation))
                .as("allows(%s, %s) must equal exact membership of key '%s' in %s",
                        resource, operation, PermissionSet.key(resource, operation), grants)
                .isEqualTo(expected);
    }

    // Feature: FOR-03-03-permission-evaluator, Property 1: Decision equals matrix membership (deny by default).
    // A resource that is absent from every grant key must be denied for every operation (deny-by-default, 2.1).
    /**
     * <b>Validates: Requirements 2.1</b>
     */
    @Property(tries = 100)
    void absentResourceIsAlwaysDenied(
            @ForAll("grantPairs") Set<String[]> grantedPairs,
            @ForAll("codes") String operation) {

        Set<String> grants = new HashSet<>();
        Set<String> knownResources = new HashSet<>();
        for (String[] pair : grantedPairs) {
            grants.add(PermissionSet.key(pair[0], pair[1]));
            knownResources.add(pair[0]);
        }

        // Craft a resource guaranteed to be absent from the granted set.
        String absentResource = "ABSENT_RESOURCE";
        while (knownResources.contains(absentResource)) {
            absentResource = absentResource + "_X";
        }

        PermissionSet set = new PermissionSet(grants);

        assertThat(set.allows(absentResource, operation))
                .as("An absent resource '%s' must be denied", absentResource)
                .isFalse();
    }

    // Feature: FOR-03-03-permission-evaluator, Property 1: Decision equals matrix membership (deny by default).
    // A resource that is granted, but only for other operations, must deny the missing operation (partial-operation, 2.2).
    /**
     * <b>Validates: Requirements 2.2</b>
     */
    @Property(tries = 100)
    void grantedResourceDeniesMissingOperation(
            @ForAll("codes") String resource,
            @ForAll("codes") String grantedOperation,
            @ForAll("codes") String queriedOperation) {

        Assume.that(!grantedOperation.equals(queriedOperation));

        PermissionSet set = new PermissionSet(Set.of(PermissionSet.key(resource, grantedOperation)));

        assertThat(set.allows(resource, queriedOperation))
                .as("Resource '%s' granted only for '%s' must deny operation '%s'",
                        resource, grantedOperation, queriedOperation)
                .isFalse();
    }

    // Feature: FOR-03-03-permission-evaluator, Property 6: Grants match resource and operation codes exactly.
    // For a single granted (R, O), allows(R2, O2) is true iff R2 equals R and O2 equals O exactly; any distinct
    // (including case-differing) pair is denied.
    /**
     * <b>Validates: Requirements 1.4</b>
     */
    @Property(tries = 100)
    void singleGrantMatchesExactly(
            @ForAll("codes") String resource,
            @ForAll("codes") String operation,
            @ForAll("codes") String queryResource,
            @ForAll("codes") String queryOperation) {

        PermissionSet set = new PermissionSet(Set.of(PermissionSet.key(resource, operation)));

        boolean expectedExactMatch = resource.equals(queryResource) && operation.equals(queryOperation);

        assertThat(set.allows(queryResource, queryOperation))
                .as("Single grant (%s, %s): allows(%s, %s) must be true only on exact match",
                        resource, operation, queryResource, queryOperation)
                .isEqualTo(expectedExactMatch);
    }

    // Feature: FOR-03-03-permission-evaluator, Property 6: Grants match resource and operation codes exactly.
    // A case-differing variant of a granted pair must be denied (exact-string comparison, 1.4).
    /**
     * <b>Validates: Requirements 1.4</b>
     */
    @Property(tries = 100)
    void caseDifferingVariantIsDenied(
            @ForAll("lowerCaseCodes") String resource,
            @ForAll("lowerCaseCodes") String operation) {

        Assume.that(!resource.toUpperCase().equals(resource) || !operation.toUpperCase().equals(operation));

        PermissionSet set = new PermissionSet(Set.of(PermissionSet.key(resource, operation)));

        String upperResource = resource.toUpperCase();
        String upperOperation = operation.toUpperCase();

        // At least one component differs by case, so the upper-cased query must not match.
        Assume.that(!(upperResource.equals(resource) && upperOperation.equals(operation)));

        assertThat(set.allows(upperResource, upperOperation))
                .as("Case-differing query (%s, %s) against grant (%s, %s) must be denied",
                        upperResource, upperOperation, resource, operation)
                .isFalse();
    }

    // --- Arbitrary Providers ---

    /** Resource/operation codes: non-blank alphabetic strings, mimicking real codes like PROJECTS, CREATE. */
    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12);
    }

    /** Lower-case codes so an upper-case variant is a genuine case-differing pair. */
    @Provide
    Arbitrary<String> lowerCaseCodes() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12);
    }

    /** Random sets of "RESOURCE:OPERATION" grant keys. */
    @Provide
    Arbitrary<Set<String>> grantSets() {
        return codes().flatMap(r -> codes().map(o -> PermissionSet.key(r, o)))
                .set()
                .ofMinSize(0)
                .ofMaxSize(20);
    }

    /** Random sets of (resource, operation) pairs used to build a matrix. */
    @Provide
    Arbitrary<Set<String[]>> grantPairs() {
        Arbitrary<String[]> pair = codes().flatMap(r -> codes().map(o -> new String[]{r, o}));
        return pair.set().ofMinSize(0).ofMaxSize(20);
    }
}
