package com.foremen.config.security;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

/**
 * Determines the {@code (resource, operation)} pair required for a matched controller handler
 * and classifies a handler's permission-annotation completeness.
 *
 * <p>Consumed by the {@code PermissionInterceptor} (per request, via {@link #resolve}) and by the
 * startup {@code PermissionAnnotationValidator} (per handler, once, via
 * {@link #classifyCompleteness}).
 *
 * <p>Resolution precedence: a method-level {@link RequiresPermission} wins; otherwise the
 * class-level {@link PermissionResource} on the bean type is combined with the method-level
 * {@link PermissionOperation} on the declaring method; otherwise the handler is Unguarded.
 */
@Component
public class PermissionResolver {

    /**
     * The {@code (resource, operation)} pair required for a handler, or produced only when the
     * handler carries a complete permission declaration. A {@code null} return from
     * {@link #resolve} means the handler is Unguarded.
     */
    public record ResolvedPair(String resource, String operation) {
    }

    /**
     * Completeness classification of a handler's permission annotations for startup validation.
     */
    public enum Completeness {
        /**
         * Carries {@link RequiresPermission}, or both {@link PermissionResource} and
         * {@link PermissionOperation}, or none of the three.
         */
        COMPLETE,
        /**
         * Bean type carries {@link PermissionResource} but this in-scope method lacks
         * {@link PermissionOperation} (and {@link RequiresPermission}).
         */
        RESOURCE_WITHOUT_OPERATION,
        /**
         * Method carries {@link PermissionOperation} but the declaring bean type lacks
         * {@link PermissionResource} (and {@link RequiresPermission}).
         */
        OPERATION_WITHOUT_RESOURCE
    }

    /**
     * Produces the {@link ResolvedPair} for a matched handler (Requirements 3.1, 4.1, 4.2, 4.3,
     * 5.1).
     *
     * <p>Precedence: {@link RequiresPermission} wins; else {@link PermissionResource} on the bean
     * type combined with {@link PermissionOperation} on the declaring method; else {@code null}
     * (Unguarded).
     *
     * @param handlerMethod the matched handler
     * @return the resolved pair, or {@code null} when the handler is Unguarded
     */
    public ResolvedPair resolve(HandlerMethod handlerMethod) {
        RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (required != null) {
            return new ResolvedPair(required.resource(), required.operation()); // Req 3.1 — precedence
        }
        String resource = resourceOf(handlerMethod);
        String operation = operationOf(handlerMethod);
        if (resource != null && operation != null) {
            return new ResolvedPair(resource, operation); // Req 4.1 — combination
        }
        return null; // Req 5.1 — Unguarded (a partial handler is rejected by startup validation)
    }

    /**
     * Classifies annotation completeness for one in-scope handler method (Requirements 6.2, 6.3,
     * 6.4, 6.6).
     *
     * @param handlerMethod the matched handler
     * @return the completeness classification
     */
    public Completeness classifyCompleteness(HandlerMethod handlerMethod) {
        if (handlerMethod.getMethodAnnotation(RequiresPermission.class) != null) {
            return Completeness.COMPLETE; // Req 6.4 — RequiresPermission is always complete
        }
        boolean hasResource = resourceOf(handlerMethod) != null;
        boolean hasOperation = operationOf(handlerMethod) != null;
        if (hasResource && !hasOperation) {
            return Completeness.RESOURCE_WITHOUT_OPERATION; // Req 6.2
        }
        if (!hasResource && hasOperation) {
            return Completeness.OPERATION_WITHOUT_RESOURCE; // Req 6.3
        }
        return Completeness.COMPLETE; // both present (guarded) or neither present (Unguarded) — Req 6.6
    }

    /**
     * Reads {@link PermissionResource} from the bean type obtained via
     * {@link HandlerMethod#getBeanType()} (Requirement 4.3).
     */
    private String resourceOf(HandlerMethod handlerMethod) {
        PermissionResource resource = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), PermissionResource.class);
        return resource != null ? resource.value() : null;
    }

    /**
     * Reads {@link PermissionOperation} from the declaring method, resolving through any bridge
     * method to the interface's declared method (Requirement 4.2).
     */
    private String operationOf(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        PermissionOperation operation = AnnotatedElementUtils.findMergedAnnotation(
                method, PermissionOperation.class);
        return operation != null ? operation.value() : null;
    }
}
