package com.foremen.config.security;

import java.lang.reflect.Method;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link PermissionResolver#classifyCompleteness(HandlerMethod)} (task 2.5).
 *
 * <p>Exercises Property 4 of the FOR-03-08 design: the completeness classification of a handler's
 * permission annotations. The three annotations that participate are:</p>
 * <ul>
 *   <li>{@link PermissionResource} &mdash; {@code @Target(TYPE)}, on the controller class;</li>
 *   <li>{@link PermissionOperation} &mdash; {@code @Target(METHOD)}, on the handler method;</li>
 *   <li>{@link RequiresPermission} &mdash; {@code @Target(METHOD)}, on the handler method.</li>
 * </ul>
 *
 * <p>Because {@link PermissionResource} is class-level, the eight present/absent combinations are
 * covered by two fixture controllers (one carrying {@code @PermissionResource}, one not), each
 * exposing the four method-level combinations of {@code @PermissionOperation} and
 * {@code @RequiresPermission}. {@link HandlerMethod} instances are built by reflection over these
 * private fixtures. The expected classification is derived independently from the boolean triple:</p>
 * <ul>
 *   <li>{@code COMPLETE} iff {@code @RequiresPermission} is present, OR both
 *       {@code @PermissionResource} and {@code @PermissionOperation} are present, OR none of the
 *       three is present;</li>
 *   <li>{@code RESOURCE_WITHOUT_OPERATION} exactly when {@code @PermissionResource} is present,
 *       {@code @PermissionOperation} is absent, and {@code @RequiresPermission} is absent;</li>
 *   <li>{@code OPERATION_WITHOUT_RESOURCE} exactly when {@code @PermissionOperation} is present,
 *       {@code @PermissionResource} is absent, and {@code @RequiresPermission} is absent.</li>
 * </ul>
 *
 * <b>Validates: Requirements 6.2, 6.3, 6.4, 6.6</b>
 */
@Tag("Feature: FOR-03-08-api-protection, Property 4: Completeness classification of a handler's annotations")
class PermissionResolverCompletenessPropertyTest {

    private final PermissionResolver resolver = new PermissionResolver();

    // Feature: FOR-03-08-api-protection, Property 4: Completeness classification of a handler's annotations.
    // Over all eight present/absent combinations of @PermissionResource / @PermissionOperation /
    // @RequiresPermission, classifyCompleteness returns COMPLETE iff @RequiresPermission is present,
    // or both @PermissionResource and @PermissionOperation are present, or none of the three is
    // present; it returns RESOURCE_WITHOUT_OPERATION / OPERATION_WITHOUT_RESOURCE exactly in the two
    // half-annotated cases with @RequiresPermission absent.
    /**
     * <b>Validates: Requirements 6.2, 6.3, 6.4, 6.6</b>
     */
    @Property(tries = 100)
    void completenessClassificationOverAllEightCombinations(
            @ForAll("annotationTriples") AnnotationTriple triple) {

        HandlerMethod handlerMethod = handlerFor(triple);

        PermissionResolver.Completeness expected = expectedCompleteness(triple);
        PermissionResolver.Completeness actual = resolver.classifyCompleteness(handlerMethod);

        assertThat(actual)
                .as("classifyCompleteness for resource=%s, operation=%s, requires=%s",
                        triple.hasResource(), triple.hasOperation(), triple.hasRequires())
                .isEqualTo(expected);
    }

    // --- Expected-classification oracle (independent of the resolver's implementation) ---

    private static PermissionResolver.Completeness expectedCompleteness(AnnotationTriple triple) {
        if (triple.hasRequires()) {
            return PermissionResolver.Completeness.COMPLETE; // Req 6.4
        }
        if (triple.hasResource() && !triple.hasOperation()) {
            return PermissionResolver.Completeness.RESOURCE_WITHOUT_OPERATION; // Req 6.2
        }
        if (!triple.hasResource() && triple.hasOperation()) {
            return PermissionResolver.Completeness.OPERATION_WITHOUT_RESOURCE; // Req 6.3
        }
        return PermissionResolver.Completeness.COMPLETE; // both present or neither present — Req 6.6
    }

    // --- HandlerMethod construction by reflection over the private fixtures ---

    private HandlerMethod handlerFor(AnnotationTriple triple) {
        Class<?> controller = triple.hasResource() ? ResourceController.class : PlainController.class;
        String methodName = methodName(triple.hasOperation(), triple.hasRequires());
        try {
            Object bean = controller.getDeclaredConstructor().newInstance();
            Method method = controller.getMethod(methodName);
            return new HandlerMethod(bean, method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String methodName(boolean hasOperation, boolean hasRequires) {
        if (hasOperation && hasRequires) {
            return "operationAndRequires";
        }
        if (hasOperation) {
            return "operationOnly";
        }
        if (hasRequires) {
            return "requiresOnly";
        }
        return "bare";
    }

    // --- Arbitrary providers: the eight present/absent combinations ---

    /** All eight combinations of the three booleans (resource, operation, requires). */
    @Provide
    Arbitrary<AnnotationTriple> annotationTriples() {
        Arbitrary<Boolean> resource = Arbitraries.of(true, false);
        Arbitrary<Boolean> operation = Arbitraries.of(true, false);
        Arbitrary<Boolean> requires = Arbitraries.of(true, false);
        return Combinators.combine(resource, operation, requires).as(AnnotationTriple::new);
    }

    /** A present/absent selection for the three participating annotations. */
    record AnnotationTriple(boolean hasResource, boolean hasOperation, boolean hasRequires) {
    }

    // --- Private fixtures: two controllers x four methods = all eight combinations ---

    /** Controller WITHOUT the class-level {@link PermissionResource}. */
    static class PlainController {

        /** No method annotation, no class @PermissionResource: none of the three present. */
        public void bare() {
            // no-op
        }

        /** Only @PermissionOperation present. */
        @PermissionOperation("READ")
        public void operationOnly() {
            // no-op
        }

        /** Only @RequiresPermission present. */
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void requiresOnly() {
            // no-op
        }

        /** @PermissionOperation and @RequiresPermission present (no class @PermissionResource). */
        @PermissionOperation("READ")
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void operationAndRequires() {
            // no-op
        }
    }

    /** Controller WITH the class-level {@link PermissionResource}. */
    @PermissionResource("USERS")
    static class ResourceController {

        /** Only class @PermissionResource present (no method annotations). */
        public void bare() {
            // no-op
        }

        /** @PermissionResource (class) and @PermissionOperation (method) present. */
        @PermissionOperation("READ")
        public void operationOnly() {
            // no-op
        }

        /** @PermissionResource (class) and @RequiresPermission (method) present. */
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void requiresOnly() {
            // no-op
        }

        /** All three present. */
        @PermissionOperation("READ")
        @RequiresPermission(resource = "PROJECTS", operation = "READ")
        public void operationAndRequires() {
            // no-op
        }
    }
}
