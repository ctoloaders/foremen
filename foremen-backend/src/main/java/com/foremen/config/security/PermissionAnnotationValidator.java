package com.foremen.config.security;

import com.foremen.config.security.PermissionResolver.Completeness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fails application startup when any Spring MVC controller carries an incomplete permission
 * declaration, so a misconfigured guard can never silently degrade into an unguarded endpoint
 * (Requirements 6.1, 6.2, 6.3, 6.5).
 *
 * <p>Runs once after all singletons are instantiated: it iterates every handler method registered
 * in the {@link RequestMappingHandlerMapping}, classifies each via
 * {@link PermissionResolver#classifyCompleteness(HandlerMethod)}, and collects an actionable
 * message for every {@link Completeness#RESOURCE_WITHOUT_OPERATION} /
 * {@link Completeness#OPERATION_WITHOUT_RESOURCE} naming the offending controller class and method.
 * Each problem is logged; when any problem exists the validator throws an
 * {@link IllegalStateException} to abort startup.
 *
 * <p>Registered by component scanning — no explicit wiring in {@link PermissionInterceptorConfig}
 * is required because it is a {@link Component} implementing {@link SmartInitializingSingleton}.
 */
@Slf4j
@Component
public class PermissionAnnotationValidator implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping handlerMapping;
    private final PermissionResolver resolver;

    public PermissionAnnotationValidator(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            PermissionResolver resolver) {
        this.handlerMapping = handlerMapping;
        this.resolver = resolver;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        for (HandlerMethod handlerMethod : handlerMethods.values()) {
            Completeness completeness = resolver.classifyCompleteness(handlerMethod);
            if (completeness == Completeness.COMPLETE) {
                continue;
            }
            String message = describe(handlerMethod, completeness);
            log.error(message);
            problems.add(message);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Incomplete @PermissionResource/@PermissionOperation declarations detected on "
                            + problems.size() + " controller handler method(s): "
                            + String.join("; ", problems));
        }
    }

    private String describe(HandlerMethod handlerMethod, Completeness completeness) {
        String controller = handlerMethod.getBeanType().getName();
        Method method = handlerMethod.getMethod();
        String signature = method.getDeclaringClass().getName() + "#" + method.getName();
        return switch (completeness) {
            case RESOURCE_WITHOUT_OPERATION -> "Controller " + controller
                    + " is annotated with @PermissionResource but its handler method " + signature
                    + " has neither @PermissionOperation nor @RequiresPermission; add "
                    + "@PermissionOperation to the method or @RequiresPermission to guard it.";
            case OPERATION_WITHOUT_RESOURCE -> "Handler method " + signature
                    + " is annotated with @PermissionOperation but its controller " + controller
                    + " has neither @PermissionResource nor @RequiresPermission on the method; add "
                    + "@PermissionResource to the controller or @RequiresPermission to the method.";
            case COMPLETE -> "";
        };
    }
}
