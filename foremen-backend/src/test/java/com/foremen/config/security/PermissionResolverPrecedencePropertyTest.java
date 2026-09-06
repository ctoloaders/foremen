package com.foremen.config.security;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for the {@code @RequiresPermission} precedence rule of {@link PermissionResolver}
 * (task 2.3).
 *
 * <p>Realizes design <b>Property 2: {@code @RequiresPermission} always wins</b>: a handler whose
 * method carries {@code @RequiresPermission(R, O)} always resolves to {@code ResolvedPair(R, O)},
 * regardless of whether the bean type carries {@link PermissionResource} and whether the method
 * carries {@link PermissionOperation}, and regardless of those annotations' values.</p>
 *
 * <p>Because annotation member values cannot be generated at runtime, the four present/absent
 * combinations of {@link PermissionResource} (on the bean type) and {@link PermissionOperation}
 * (on the method) are materialized as a fixed set of pre-annotated fixture controllers/methods,
 * and the property generator selects among them per try. Every fixture method carries the same
 * {@code @RequiresPermission(RESOURCE, OPERATION)} so the expected {@code ResolvedPair} is fixed
 * and the secondary annotations are proven to be ignored.</p>
 *
 * @see PermissionResolver#resolve(HandlerMethod)
 */
class PermissionResolverPrecedencePropertyTest {

    /** The resource on every fixture method's {@code @RequiresPermission}; must always win. */
    private static final String REQUIRED_RESOURCE = "REQ_RESOURCE";

    /** The operation on every fixture method's {@code @RequiresPermission}; must always win. */
    private static final String REQUIRED_OPERATION = "REQ_OPERATION";

    /** Distractor values carried by the secondary annotations, which must be ignored. */
    private static final String OTHER_RESOURCE = "OTHER_RESOURCE";
    private static final String OTHER_OPERATION = "OTHER_OPERATION";

    private final PermissionResolver resolver = new PermissionResolver();

    // Feature: FOR-03-08-api-protection, Property 2: @RequiresPermission always wins.
    // For a method carrying @RequiresPermission(R, O), across all present/absent combinations of
    // @PermissionResource (on the bean type) and @PermissionOperation (on the method), resolve
    // returns ResolvedPair(R, O) from the @RequiresPermission and ignores the other annotations.
    /**
     * <b>Validates: Requirements 3.1, 3.2</b>
     */
    @Property(tries = 100)
    void requiresPermissionAlwaysWins(@ForAll("secondaryAnnotationShapes") Shape shape) {
        HandlerMethod handlerMethod = handlerFor(shape);

        PermissionResolver.ResolvedPair pair = resolver.resolve(handlerMethod);

        assertThat(pair)
                .as("shape %s: @RequiresPermission must win and yield (%s, %s), ignoring the secondary annotations",
                        shape, REQUIRED_RESOURCE, REQUIRED_OPERATION)
                .isEqualTo(new PermissionResolver.ResolvedPair(REQUIRED_RESOURCE, REQUIRED_OPERATION));
    }

    /**
     * The four present/absent combinations of the secondary annotations that must be ignored when
     * {@code @RequiresPermission} is present.
     */
    @Provide
    Arbitrary<Shape> secondaryAnnotationShapes() {
        return Arbitraries.of(Shape.values());
    }

    /**
     * Builds a real {@link HandlerMethod} over the fixture controller/method matching the requested
     * present/absent combination of the secondary annotations. Every fixture method carries
     * {@code @RequiresPermission(REQUIRED_RESOURCE, REQUIRED_OPERATION)}.
     */
    private HandlerMethod handlerFor(Shape shape) {
        Object bean = shape.resourcePresent
                ? new WithResourceController()
                : new WithoutResourceController();
        String methodName = shape.operationPresent ? "withOperation" : "withoutOperation";
        Method method = findMethod(bean.getClass(), methodName);
        return new HandlerMethod(bean, method);
    }

    private Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Fixture method not found: " + type.getName() + "#" + name, e);
        }
    }

    /** The present/absent combination of the secondary (non-{@code @RequiresPermission}) annotations. */
    private enum Shape {
        NEITHER(false, false),
        RESOURCE_ONLY(true, false),
        OPERATION_ONLY(false, true),
        BOTH(true, true);

        private final boolean resourcePresent;
        private final boolean operationPresent;

        Shape(boolean resourcePresent, boolean operationPresent) {
            this.resourcePresent = resourcePresent;
            this.operationPresent = operationPresent;
        }
    }

    // ------------------------------------------------------------------
    // Fixture controllers/methods (private, not shared across test files)
    // ------------------------------------------------------------------

    /** Bean type WITHOUT @PermissionResource, exposing methods with/without @PermissionOperation. */
    static class WithoutResourceController {

        @GetMapping("/without-operation")
        @RequiresPermission(resource = REQUIRED_RESOURCE, operation = REQUIRED_OPERATION)
        public void withoutOperation() {
            // Fixture handler: body intentionally empty; only its annotations are under test.
        }

        @GetMapping("/with-operation")
        @PermissionOperation(OTHER_OPERATION)
        @RequiresPermission(resource = REQUIRED_RESOURCE, operation = REQUIRED_OPERATION)
        public void withOperation() {
            // Fixture handler: body intentionally empty; only its annotations are under test.
        }
    }

    /** Bean type WITH @PermissionResource, exposing methods with/without @PermissionOperation. */
    @PermissionResource(OTHER_RESOURCE)
    static class WithResourceController {

        @GetMapping("/without-operation")
        @RequiresPermission(resource = REQUIRED_RESOURCE, operation = REQUIRED_OPERATION)
        public void withoutOperation() {
            // Fixture handler: body intentionally empty; only its annotations are under test.
        }

        @GetMapping("/with-operation")
        @PermissionOperation(OTHER_OPERATION)
        @RequiresPermission(resource = REQUIRED_RESOURCE, operation = REQUIRED_OPERATION)
        public void withOperation() {
            // Fixture handler: body intentionally empty; only its annotations are under test.
        }
    }
}
