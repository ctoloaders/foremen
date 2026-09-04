package com.foremen.service.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.PermissionView;
import com.foremen.util.MeETag;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link MeETag#compute(CurrentUserResponse)} (Requirement 13 backend
 * ETag dependency, 13.1, 13.2).
 *
 * <p>Two universal properties are exercised:
 * <ul>
 *   <li><b>Stability under content</b> — the ETag is order-independent: for the same semantic
 *       content but with permissions and operations supplied in a different insertion order,
 *       {@code compute} returns the same ETag.</li>
 *   <li><b>Change-on-change</b> — when any semantic field (userId, name, email, roleCode, or the
 *       permission set) differs, the ETag differs.</li>
 * </ul>
 *
 * The permission {@link Set} is materialised as a {@link LinkedHashSet} so that the two DTOs
 * compared in the stability property genuinely differ in iteration order — otherwise a plain
 * {@code HashSet} could normalise the order and mask a missing sort inside the utility.
 *
 * Property 8: The /me ETag is stable under content and changes on change (backend).
 */
@Tag("Feature: FOR-03-06-frontend-auth, Property 8: The /me ETag is stable under content and changes on change (backend)")
class MeETagStabilityPropertyTest {

    /** A semantic snapshot of the fields that drive the ETag, independent of ordering. */
    private record UserContent(long id, String name, String email, String roleCode,
                               List<PermissionView> permissions) {}

    // --- Generators ---

    @Provide
    Arbitrary<String> text() {
        // Non-null strings including the empty string and a few pipe/colon/semicolon-ish
        // characters that appear in the canonical separators, to stress delimiter handling.
        return Arbitraries.strings().ascii().ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> resourceName() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
    }

    @Provide
    Arbitrary<String> operationName() {
        return Arbitraries.of("READ", "CREATE", "UPDATE", "DELETE", "EXPORT", "APPROVE");
    }

    @Provide
    Arbitrary<PermissionView> permission() {
        Arbitrary<Set<String>> ops = operationName().set().ofMinSize(1).ofMaxSize(6)
                // Materialise as an insertion-ordered set so shuffling is observable.
                .map(LinkedHashSet::new);
        return Combinators.combine(resourceName(), ops).as(PermissionView::new);
    }

    /**
     * A list of permissions with <em>distinct</em> resources so that shuffling produces a
     * genuinely different iteration order without collapsing entries.
     */
    @Provide
    Arbitrary<List<PermissionView>> permissionList() {
        return permission().list().uniqueElements(PermissionView::resource).ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<UserContent> userContent() {
        return Combinators.combine(
                Arbitraries.longs(),
                text(),
                text(),
                text(),
                permissionList()
        ).as(UserContent::new);
    }

    // --- Helpers ---

    private static CurrentUserResponse toResponse(UserContent c, List<PermissionView> orderedPerms) {
        // Preserve the supplied ordering by using a LinkedHashSet.
        Set<PermissionView> perms = new LinkedHashSet<>(orderedPerms);
        return new CurrentUserResponse(c.id(), c.name(), c.email(), c.roleCode(), perms);
    }

    // --- Properties ---

    /**
     * Stability: the same semantic content with permissions (and operations within each
     * permission) in a different order yields the same ETag.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    void etagIsOrderIndependent(@ForAll("userContent") UserContent content,
                                @ForAll long seed) {
        CurrentUserResponse original = toResponse(content, content.permissions());

        // Build a reordered copy: shuffle the permission list and reverse operations within each.
        List<PermissionView> shuffled = new ArrayList<>();
        for (PermissionView p : content.permissions()) {
            List<String> ops = new ArrayList<>(p.operations());
            Collections.reverse(ops);
            shuffled.add(new PermissionView(p.resource(), new LinkedHashSet<>(ops)));
        }
        Collections.shuffle(shuffled, new java.util.Random(seed));
        CurrentUserResponse reordered = toResponse(content, shuffled);

        assertThat(MeETag.compute(reordered)).isEqualTo(MeETag.compute(original));
    }

    /**
     * Change-on-change (identity/profile/role fields): mutating exactly one of userId, name,
     * email, or roleCode to a value that differs from the original produces a different ETag.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    void etagChangesWhenScalarFieldChanges(@ForAll("userContent") UserContent content,
                                           @ForAll @IntRange(min = 0, max = 3) int fieldIndex,
                                           @ForAll("text") String replacement,
                                           @ForAll long replacementId) {
        CurrentUserResponse original = toResponse(content, content.permissions());

        CurrentUserResponse mutated;
        switch (fieldIndex) {
            case 0 -> {
                // Ensure the id actually differs.
                long newId = replacementId == content.id() ? content.id() + 1 : replacementId;
                mutated = new CurrentUserResponse(newId, content.name(), content.email(),
                        content.roleCode(), new LinkedHashSet<>(content.permissions()));
            }
            case 1 -> {
                String newName = differing(content.name(), replacement);
                mutated = new CurrentUserResponse(content.id(), newName, content.email(),
                        content.roleCode(), new LinkedHashSet<>(content.permissions()));
            }
            case 2 -> {
                String newEmail = differing(content.email(), replacement);
                mutated = new CurrentUserResponse(content.id(), content.name(), newEmail,
                        content.roleCode(), new LinkedHashSet<>(content.permissions()));
            }
            default -> {
                String newRole = differing(content.roleCode(), replacement);
                mutated = new CurrentUserResponse(content.id(), content.name(), content.email(),
                        newRole, new LinkedHashSet<>(content.permissions()));
            }
        }

        assertThat(MeETag.compute(mutated)).isNotEqualTo(MeETag.compute(original));
    }

    /**
     * Change-on-change (permission set): adding a permission whose resource is not already
     * present changes the permission set semantically and therefore the ETag.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    void etagChangesWhenPermissionSetChanges(@ForAll("userContent") UserContent content) {
        CurrentUserResponse original = toResponse(content, content.permissions());

        // Choose a resource name that is not already present so the set genuinely grows.
        String newResource = "z-new-resource";
        for (PermissionView p : content.permissions()) {
            if (newResource.equals(p.resource())) {
                newResource = newResource + "x";
            }
        }
        List<PermissionView> augmented = new ArrayList<>(content.permissions());
        augmented.add(new PermissionView(newResource, new LinkedHashSet<>(Set.of("READ"))));
        CurrentUserResponse mutated = toResponse(content, augmented);

        assertThat(MeETag.compute(mutated)).isNotEqualTo(MeETag.compute(original));
    }

    /** Returns a value guaranteed to differ from {@code current}. */
    private static String differing(String current, String candidate) {
        String safeCurrent = current == null ? "" : current;
        if (!safeCurrent.equals(candidate)) {
            return candidate;
        }
        return candidate + "_x";
    }
}
