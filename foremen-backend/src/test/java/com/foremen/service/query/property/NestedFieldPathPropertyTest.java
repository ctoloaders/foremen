package com.foremen.service.query.property;

import com.foremen.service.query.QueryOperator;
import com.foremen.service.query.QueryToken;
import com.foremen.service.query.SpecificationBuilder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.data.jpa.domain.Specification;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property 6: Nested Field Path Parsing with Automatic Join Detection
 *
 * For any valid dot-notation field path string (1 to N dot-separated segments where each
 * segment is a valid Java identifier), the {@code SpecificationBuilder.resolvePath} method SHALL:
 * <ul>
 *   <li>For collection-typed segments: use {@code join()} and set {@code query.distinct(true)}</li>
 *   <li>For association-typed segments: use {@code join()} for navigation</li>
 *   <li>For simple attribute segments: use {@code get()}</li>
 *   <li>The same JOIN instance SHALL be reused for repeated references to the same collection</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5</b></p>
 */
class NestedFieldPathPropertyTest {

    // --- Segment type enum for generation ---

    enum SegmentType {
        SIMPLE,      // plain attribute — triggers get()
        ASSOCIATION, // singular association — triggers join() for navigation
        COLLECTION   // collection association — triggers join() + distinct
    }

    /**
     * Represents a generated path definition with segments and their expected types.
     */
    record PathDefinition(List<String> segments, List<SegmentType> segmentTypes) {
        String toFieldPath() {
            return String.join(".", segments);
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> validIdentifiers() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(8);
    }

    // --- Mock infrastructure ---

    /**
     * Creates a mocked JPA environment that tracks get() and join() calls
     * based on the provided PathDefinition.
     *
     * Uses EntityType for Root's model (Root.getModel() returns EntityType)
     * and ManagedType for Join's model (Join.getModel() returns ManagedType).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private MockEnvironment createMockEnvironment(PathDefinition pathDef) {
        Root root = mock(Root.class);
        CriteriaQuery query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);

        // Track calls
        List<String> getCalls = Collections.synchronizedList(new ArrayList<>());
        List<String> joinCalls = Collections.synchronizedList(new ArrayList<>());

        // Setup the mock chain based on path definition
        // We track the current "From" as either the root or a Join
        Object currentFromObj = root; // Root or Join
        boolean isRoot = true;
        Map<String, Join> joinMap = new HashMap<>();

        for (int i = 0; i < pathDef.segments().size(); i++) {
            String segment = pathDef.segments().get(i);
            SegmentType type = pathDef.segmentTypes().get(i);
            boolean isTerminal = (i == pathDef.segments().size() - 1);

            if (isTerminal) {
                // Terminal segment: always uses get()
                Path terminalPath = mock(Path.class, "path_" + segment);
                doReturn(String.class).when(terminalPath).getJavaType();
                if (isRoot) {
                    doAnswer(inv -> {
                        getCalls.add(segment);
                        return terminalPath;
                    }).when((Root) currentFromObj).get(segment);
                } else {
                    doAnswer(inv -> {
                        getCalls.add(segment);
                        return terminalPath;
                    }).when((Join) currentFromObj).get(segment);
                }
            } else {
                // Non-terminal segment: setup model + attribute
                Attribute attr = mock(Attribute.class, "attr_" + segment);

                if (isRoot) {
                    EntityType entityType = mock(EntityType.class, "entityType_" + i);
                    doReturn(entityType).when((Root) currentFromObj).getModel();
                    doReturn(attr).when(entityType).getAttribute(segment);
                } else {
                    // Join.getModel() can return ManagedType or EntityType depending on impl
                    // Use EntityType mock which extends ManagedType to satisfy both
                    EntityType joinEntityType = mock(EntityType.class, "joinEntityType_" + i);
                    doReturn(joinEntityType).when((Join) currentFromObj).getModel();
                    doReturn(attr).when(joinEntityType).getAttribute(segment);
                }

                switch (type) {
                    case COLLECTION -> {
                        doReturn(true).when(attr).isCollection();
                        doReturn(true).when(attr).isAssociation();

                        // Create a join mock for collections
                        Join joinMock = mock(Join.class, "join_" + segment);
                        Attribute joinAttr = mock(Attribute.class, "joinAttr_" + segment);
                        doReturn(segment).when(joinAttr).getName();
                        doReturn(joinAttr).when(joinMock).getAttribute();

                        // Setup getJoins() to return existing joins
                        Set<Join> existingJoins = new HashSet<>(joinMap.values());
                        if (isRoot) {
                            doReturn(existingJoins).when((Root) currentFromObj).getJoins();
                            doAnswer(inv -> {
                                joinCalls.add(segment);
                                return joinMock;
                            }).when((Root) currentFromObj).join(segment);
                        } else {
                            doReturn(existingJoins).when((Join) currentFromObj).getJoins();
                            doAnswer(inv -> {
                                joinCalls.add(segment);
                                return joinMock;
                            }).when((Join) currentFromObj).join(segment);
                        }

                        joinMap.put(segment, joinMock);
                        currentFromObj = joinMock;
                        isRoot = false;
                    }
                    case ASSOCIATION -> {
                        doReturn(false).when(attr).isCollection();
                        doReturn(true).when(attr).isAssociation();

                        // The code calls get() first, then sees isAssociation and calls join()
                        Path getPath = mock(Path.class, "getpath_" + segment);
                        Join joinMock = mock(Join.class, "join_" + segment);
                        Attribute joinAttr = mock(Attribute.class, "joinAttr_" + segment);
                        doReturn(segment).when(joinAttr).getName();
                        doReturn(joinAttr).when(joinMock).getAttribute();

                        Set<Join> existingJoins = new HashSet<>(joinMap.values());
                        if (isRoot) {
                            doAnswer(inv -> {
                                getCalls.add(segment);
                                return getPath;
                            }).when((Root) currentFromObj).get(segment);
                            doReturn(existingJoins).when((Root) currentFromObj).getJoins();
                            doAnswer(inv -> {
                                joinCalls.add(segment);
                                return joinMock;
                            }).when((Root) currentFromObj).join(segment);
                        } else {
                            doAnswer(inv -> {
                                getCalls.add(segment);
                                return getPath;
                            }).when((Join) currentFromObj).get(segment);
                            doReturn(existingJoins).when((Join) currentFromObj).getJoins();
                            doAnswer(inv -> {
                                joinCalls.add(segment);
                                return joinMock;
                            }).when((Join) currentFromObj).join(segment);
                        }

                        joinMap.put(segment, joinMock);
                        currentFromObj = joinMock;
                        isRoot = false;
                    }
                    case SIMPLE -> {
                        doReturn(false).when(attr).isCollection();
                        doReturn(false).when(attr).isAssociation();

                        Path getPath = mock(Path.class, "path_" + segment);
                        if (isRoot) {
                            doAnswer(inv -> {
                                getCalls.add(segment);
                                return getPath;
                            }).when((Root) currentFromObj).get(segment);
                        } else {
                            doAnswer(inv -> {
                                getCalls.add(segment);
                                return getPath;
                            }).when((Join) currentFromObj).get(segment);
                        }

                        // After a simple non-terminal, no From context available
                        // Setup the get chain for remaining segments on the path
                        Path currentPath = getPath;
                        for (int j = i + 1; j < pathDef.segments().size(); j++) {
                            String nextSeg = pathDef.segments().get(j);
                            Path nextPath = mock(Path.class, "path_" + nextSeg + "_" + j);
                            if (j == pathDef.segments().size() - 1) {
                                doReturn(String.class).when(nextPath).getJavaType();
                            }
                            Path pathForGet = currentPath;
                            doAnswer(inv -> {
                                getCalls.add(nextSeg);
                                return nextPath;
                            }).when(pathForGet).get(nextSeg);
                            currentPath = nextPath;
                        }
                        // Signal that we've handled all remaining segments
                        currentFromObj = null;
                    }
                }

                // If currentFromObj became null, break the loop — remaining handled
                if (currentFromObj == null) break;
            }
        }

        // Setup CriteriaBuilder to return a predicate for any operation
        doReturn(predicate).when(cb).equal(any(), any());
        doReturn(predicate).when(cb).like(any(Expression.class), anyString());

        return new MockEnvironment(root, query, cb, getCalls, joinCalls);
    }

    record MockEnvironment(
            Root root,
            CriteriaQuery query,
            CriteriaBuilder cb,
            List<String> getCalls,
            List<String> joinCalls
    ) {}

    // --- Property Tests ---

    /**
     * Property: Collection-typed segments trigger join() and query.distinct(true).
     *
     * For any path containing a collection-typed non-terminal segment,
     * SpecificationBuilder SHALL use join() for that segment and set distinct to true.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Property(tries = 100)
    void collectionSegments_triggerJoinAndDistinct(
            @ForAll("pathsWithCollection") PathDefinition pathDef) {

        MockEnvironment env = createMockEnvironment(pathDef);

        // Build and execute the specification
        QueryToken.Filter filter = new QueryToken.Filter(
                pathDef.toFieldPath(), QueryOperator.EQUALS, "testValue");
        Specification spec = SpecificationBuilder.buildPredicate(filter, Object.class);
        spec.toPredicate(env.root(), env.query(), env.cb());

        // Verify: collection segments triggered join
        for (int i = 0; i < pathDef.segments().size() - 1; i++) {
            if (pathDef.segmentTypes().get(i) == SegmentType.COLLECTION) {
                assertThat(env.joinCalls())
                        .as("Collection segment '%s' should trigger a join() call", pathDef.segments().get(i))
                        .contains(pathDef.segments().get(i));
            }
        }

        // Verify distinct was set when collection is present
        verify(env.query(), atLeastOnce()).distinct(true);
    }

    /**
     * Property: Association-typed segments trigger join() for navigation.
     *
     * For any path containing an association-typed non-terminal segment,
     * SpecificationBuilder SHALL use join() to navigate through it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Property(tries = 100)
    void associationSegments_triggerJoin(
            @ForAll("pathsWithAssociation") PathDefinition pathDef) {

        MockEnvironment env = createMockEnvironment(pathDef);

        QueryToken.Filter filter = new QueryToken.Filter(
                pathDef.toFieldPath(), QueryOperator.EQUALS, "testValue");
        Specification spec = SpecificationBuilder.buildPredicate(filter, Object.class);
        spec.toPredicate(env.root(), env.query(), env.cb());

        // Verify: association segments triggered join
        for (int i = 0; i < pathDef.segments().size() - 1; i++) {
            if (pathDef.segmentTypes().get(i) == SegmentType.ASSOCIATION) {
                assertThat(env.joinCalls())
                        .as("Association segment '%s' should trigger join()", pathDef.segments().get(i))
                        .contains(pathDef.segments().get(i));
            }
        }
    }

    /**
     * Property: Simple attribute segments use get() (not join).
     *
     * For paths where all non-terminal segments are simple attributes,
     * SpecificationBuilder SHALL use only get() calls and never join().
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Property(tries = 100)
    void simpleSegments_triggerGetOnly(
            @ForAll("pathsAllSimple") PathDefinition pathDef) {

        MockEnvironment env = createMockEnvironment(pathDef);

        QueryToken.Filter filter = new QueryToken.Filter(
                pathDef.toFieldPath(), QueryOperator.EQUALS, "testValue");
        Specification spec = SpecificationBuilder.buildPredicate(filter, Object.class);
        spec.toPredicate(env.root(), env.query(), env.cb());

        // Verify: no join calls were made
        assertThat(env.joinCalls())
                .as("Simple-only paths should never trigger join()")
                .isEmpty();

        // Verify: all segments used get()
        for (String segment : pathDef.segments()) {
            assertThat(env.getCalls())
                    .as("Segment '%s' should use get()", segment)
                    .contains(segment);
        }

        // Verify: distinct was NOT set
        verify(env.query(), never()).distinct(true);
    }

    /**
     * Property: Same JOIN instance is reused for repeated references to the same collection.
     *
     * When two filters reference the same collection path, the same Join instance
     * should be reused (not a new join created each time).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Property(tries = 100)
    void sameJoinReused_forRepeatedCollectionReferences(
            @ForAll("validIdentifiers") String collectionField,
            @ForAll("validIdentifiers") String terminalField1,
            @ForAll("validIdentifiers") String terminalField2) {

        // Ensure distinct names
        if (collectionField.equals(terminalField1) || collectionField.equals(terminalField2)
                || terminalField1.equals(terminalField2)) {
            return; // skip degenerate case
        }

        // Setup: root with a collection attribute
        Root root = mock(Root.class);
        CriteriaQuery query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);

        EntityType entityType = mock(EntityType.class);
        doReturn(entityType).when(root).getModel();

        Attribute collectionAttr = mock(Attribute.class);
        doReturn(true).when(collectionAttr).isCollection();
        doReturn(true).when(collectionAttr).isAssociation();
        doReturn(collectionAttr).when(entityType).getAttribute(collectionField);

        // Create a single Join mock
        Join joinMock = mock(Join.class);
        Attribute joinAttrMock = mock(Attribute.class);
        doReturn(collectionField).when(joinAttrMock).getName();
        doReturn(joinAttrMock).when(joinMock).getAttribute();

        // First call: getJoins() returns empty → creates new join
        // Second call: getJoins() returns the existing join → reuses it
        doReturn(Collections.emptySet())
                .doReturn(Set.of(joinMock))
                .when(root).getJoins();
        doReturn(joinMock).when(root).join(collectionField);

        // Terminal get() calls
        Path path1 = mock(Path.class);
        Path path2 = mock(Path.class);
        doReturn(String.class).when(path1).getJavaType();
        doReturn(String.class).when(path2).getJavaType();
        doReturn(path1).when(joinMock).get(terminalField1);
        doReturn(path2).when(joinMock).get(terminalField2);

        doReturn(predicate).when(cb).equal(any(), any());

        // Execute first filter: collection.terminal1
        String field1 = collectionField + "." + terminalField1;
        QueryToken.Filter filter1 = new QueryToken.Filter(field1, QueryOperator.EQUALS, "val1");
        Specification spec1 = SpecificationBuilder.buildPredicate(filter1, Object.class);
        spec1.toPredicate(root, query, cb);

        // Execute second filter: collection.terminal2
        String field2 = collectionField + "." + terminalField2;
        QueryToken.Filter filter2 = new QueryToken.Filter(field2, QueryOperator.EQUALS, "val2");
        Specification spec2 = SpecificationBuilder.buildPredicate(filter2, Object.class);
        spec2.toPredicate(root, query, cb);

        // Verify: join was only called ONCE (reused for second call)
        verify(root, times(1)).join(collectionField);
    }

    // --- Specialized path generators ---

    /**
     * Generates paths that always contain at least one collection-typed segment.
     *
     * Note: a SIMPLE non-terminal segment kills the From context, so any COLLECTION
     * or ASSOCIATION segments must appear before any SIMPLE non-terminal.
     * We ensure segments before the collection index are ASSOCIATION (which preserves From)
     * and segments after are SIMPLE (which is fine — the collection already did its join).
     */
    @Provide
    Arbitrary<PathDefinition> pathsWithCollection() {
        return Arbitraries.integers().between(2, 4).flatMap(length -> {
            Arbitrary<String> identifiers = Arbitraries.strings()
                    .withCharRange('a', 'z')
                    .ofMinLength(2)
                    .ofMaxLength(8);

            // At least one non-terminal segment must be COLLECTION
            return Combinators.combine(
                    identifiers.list().ofSize(length),
                    Arbitraries.integers().between(0, length - 2) // index of the collection segment
            ).flatAs((segs, collectionIdx) -> {
                List<String> uniqueSegs = makeUnique(segs);

                // Segments before collectionIdx must preserve From (ASSOCIATION or COLLECTION)
                // Segments after collectionIdx must also preserve From (ASSOCIATION or COLLECTION)
                // because a SIMPLE non-terminal kills From context for subsequent segments.
                Arbitrary<SegmentType> preserveFromTypes = Arbitraries.of(SegmentType.ASSOCIATION, SegmentType.COLLECTION);

                int preCount = collectionIdx;
                int postCount = length - 2 - collectionIdx; // non-terminal segments after collection

                Arbitrary<List<SegmentType>> preList = preCount > 0
                        ? preserveFromTypes.list().ofSize(preCount)
                        : Arbitraries.just(List.of());
                Arbitrary<List<SegmentType>> postList = postCount > 0
                        ? preserveFromTypes.list().ofSize(postCount)
                        : Arbitraries.just(List.of());

                return Combinators.combine(preList, postList).as((pre, post) -> {
                    List<SegmentType> fullTypes = new ArrayList<>(pre);
                    fullTypes.add(SegmentType.COLLECTION); // the mandatory collection
                    fullTypes.addAll(post);
                    fullTypes.add(SegmentType.SIMPLE); // terminal is always SIMPLE
                    return new PathDefinition(uniqueSegs, fullTypes);
                });
            });
        });
    }

    /**
     * Generates paths that always contain at least one association-typed segment
     * (but no collections, to isolate the association behavior).
     *
     * Note: a SIMPLE non-terminal segment kills the From context for all subsequent segments,
     * so the association must appear BEFORE any SIMPLE non-terminal. We place ASSOCIATION
     * segments before any SIMPLE ones to ensure they have From context available.
     */
    @Provide
    Arbitrary<PathDefinition> pathsWithAssociation() {
        return Arbitraries.integers().between(2, 4).flatMap(length -> {
            Arbitrary<String> identifiers = Arbitraries.strings()
                    .withCharRange('a', 'z')
                    .ofMinLength(2)
                    .ofMaxLength(8);

            return Combinators.combine(
                    identifiers.list().ofSize(length),
                    Arbitraries.integers().between(0, length - 2) // index of the association segment
            ).flatAs((segs, assocIdx) -> {
                List<String> uniqueSegs = makeUnique(segs);

                // Association at assocIdx; segments BEFORE assocIdx must be ASSOCIATION too
                // (not SIMPLE, because SIMPLE kills From context).
                // Segments AFTER assocIdx can be SIMPLE (they'll just use get() on Path).
                return Arbitraries.just(uniqueSegs).map(segments -> {
                    List<SegmentType> types = new ArrayList<>();
                    for (int i = 0; i < length - 1; i++) {
                        if (i <= assocIdx) {
                            types.add(SegmentType.ASSOCIATION);
                        } else {
                            types.add(SegmentType.SIMPLE);
                        }
                    }
                    types.add(SegmentType.SIMPLE); // terminal
                    return new PathDefinition(segments, types);
                });
            });
        });
    }

    /**
     * Generates paths where ALL non-terminal segments are SIMPLE (no joins needed).
     */
    @Provide
    Arbitrary<PathDefinition> pathsAllSimple() {
        return Arbitraries.integers().between(2, 4).flatMap(length -> {
            Arbitrary<String> identifiers = Arbitraries.strings()
                    .withCharRange('a', 'z')
                    .ofMinLength(2)
                    .ofMaxLength(8);

            return identifiers.list().ofSize(length).map(segs -> {
                List<String> uniqueSegs = makeUnique(segs);
                List<SegmentType> types = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    types.add(SegmentType.SIMPLE);
                }
                return new PathDefinition(uniqueSegs, types);
            });
        });
    }

    // --- Utility ---

    private static List<String> makeUnique(List<String> segments) {
        List<String> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : segments) {
            while (seen.contains(s)) {
                s = s + "x";
            }
            seen.add(s);
            unique.add(s);
        }
        return unique;
    }
}
