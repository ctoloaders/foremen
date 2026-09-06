package com.foremen.util;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.AdminController;
import com.foremen.controller.AdminReadOnlyController;
import com.foremen.service.AdminService;
import com.foremen.service.ReadOnlyAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Startup registry mapping a managed entity type to its API resource code and base path.
 *
 * <p>At startup it scans every Spring controller bean implementing {@link AdminController} or
 * {@link AdminReadOnlyController}, reading each controller's class-level
 * {@link PermissionResource} value (the resource code, e.g. {@code "ROLES"}) and its
 * {@link RequestMapping} base path (e.g. {@code "/api/roles"}), and resolves the managed entity
 * type through the controller's service ({@code getService().getDaoModelClass()}). The resulting
 * map lets metadata resolution answer, for a {@code @ManyToOne}/{@code @OneToOne} target entity,
 * "which resource code and options endpoint does this entity live behind?".
 *
 * <p>A small declarative fallback map covers entity types the scan cannot resolve (e.g. a target
 * entity exposed by a controller lacking {@code @PermissionResource}, or not exposed at all).
 * The scan always wins over the fallback when both provide an entry.
 *
 * <p>The registry is populated in {@link #afterSingletonsInstantiated()} (after all controller
 * beans and their services are instantiated) and is read-only thereafter, so lookups are
 * thread-safe.
 */
@Slf4j
@Component
public class ReferenceResourceRegistry implements SmartInitializingSingleton {

    /**
     * Resolved reference target for a managed entity type.
     *
     * @param resourceCode the resource code from {@code @PermissionResource} (e.g. {@code "ROLES"})
     * @param basePath     the controller's {@code @RequestMapping} base path (e.g. {@code "/api/roles"})
     */
    public record Reference(String resourceCode, String basePath) {}

    private final ApplicationContext applicationContext;

    /** Populated once at startup; read-only afterwards. */
    private final Map<Class<?>, Reference> registry = new ConcurrentHashMap<>();

    /**
     * Declarative fallback for entity types the controller scan cannot resolve. Kept intentionally
     * small; entries are added here only when an entity is referenced but not exposed by a
     * {@code @PermissionResource}-annotated CRUD controller.
     */
    private static final Map<Class<?>, Reference> FALLBACK = buildFallback();

    private static Map<Class<?>, Reference> buildFallback() {
        // No fallback entries are required today: every referenceable target entity is exposed by a
        // controller carrying @PermissionResource. Additional mappings can be declared here.
        return Map.of();
    }

    public ReferenceResourceRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void afterSingletonsInstantiated() {
        Map<Class<?>, Reference> scanned = new HashMap<>();

        Map<String, AdminController> adminBeans = applicationContext.getBeansOfType(AdminController.class);
        for (AdminController<?, ?, ?, ?, ?, ?, ?, ?, ?, ?> bean : adminBeans.values()) {
            register(scanned, bean, entityTypeOf(bean));
        }

        Map<String, AdminReadOnlyController> readOnlyBeans =
                applicationContext.getBeansOfType(AdminReadOnlyController.class);
        for (AdminReadOnlyController<?, ?, ?, ?> bean : readOnlyBeans.values()) {
            register(scanned, bean, entityTypeOf(bean));
        }

        registry.putAll(scanned);
        log.info("ReferenceResourceRegistry initialized with {} entity mapping(s): {}",
                registry.size(), registry.keySet().stream().map(Class::getSimpleName).sorted().toList());

        // Hand this registry to the static metadata resolver so reference descriptors can be
        // emitted. Done here (after the scan) so lookups already resolve real mappings.
        EntityMetadataResolver.setReferenceRegistry(this);
    }

    private void register(Map<Class<?>, Reference> target, Object bean, Class<?> entityType) {
        if (entityType == null) {
            return;
        }
        Class<?> controllerClass = resolveTargetClass(bean);
        PermissionResource permissionResource =
                AnnotatedElementUtils.findMergedAnnotation(controllerClass, PermissionResource.class);
        RequestMapping requestMapping =
                AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping.class);

        if (permissionResource == null) {
            // Intentionally unguarded / non-managed controller — not a reference target.
            return;
        }
        String resourceCode = permissionResource.value();
        String basePath = firstPath(requestMapping);
        if (basePath == null) {
            log.warn("Controller {} has @PermissionResource(\"{}\") but no @RequestMapping base path; "
                    + "skipping reference registration for {}",
                    controllerClass.getSimpleName(), resourceCode, entityType.getSimpleName());
            return;
        }

        Reference existing = target.putIfAbsent(entityType, new Reference(resourceCode, basePath));
        if (existing != null) {
            log.warn("Entity {} is exposed by more than one controller ({} vs already-registered {}/{}); "
                    + "keeping the first registration",
                    entityType.getSimpleName(), controllerClass.getSimpleName(),
                    existing.resourceCode(), existing.basePath());
        }
    }

    private static String firstPath(RequestMapping requestMapping) {
        if (requestMapping == null) {
            return null;
        }
        String[] paths = requestMapping.value().length > 0 ? requestMapping.value() : requestMapping.path();
        return paths.length > 0 ? paths[0] : null;
    }

    private Class<?> resolveTargetClass(Object bean) {
        // Unwrap Spring proxies (CGLIB/JDK) so annotations on the concrete controller are visible.
        return org.springframework.aop.support.AopUtils.getTargetClass(bean);
    }

    private Class<?> entityTypeOf(AdminController<?, ?, ?, ?, ?, ?, ?, ?, ?, ?> controller) {
        try {
            AdminService<?, ?, ?, ?> service = controller.getService();
            return service != null ? service.getDaoModelClass() : null;
        } catch (RuntimeException ex) {
            log.warn("Could not resolve entity type for controller {}: {}",
                    controller.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    private Class<?> entityTypeOf(AdminReadOnlyController<?, ?, ?, ?> controller) {
        try {
            ReadOnlyAdminService<?, ?, ?, ?> service = controller.getService();
            return service != null ? service.getDaoModelClass() : null;
        } catch (RuntimeException ex) {
            log.warn("Could not resolve entity type for read-only controller {}: {}",
                    controller.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    /**
     * Looks up the API resource code and base path for a managed entity type.
     *
     * @param entityType the JPA entity class (e.g. {@code RoleEntity.class})
     * @return the resolved {@link Reference}, or empty when neither the startup scan nor the
     *         declarative fallback knows the type
     */
    public Optional<Reference> lookup(Class<?> entityType) {
        if (entityType == null) {
            return Optional.empty();
        }
        Reference scanned = registry.get(entityType);
        if (scanned != null) {
            return Optional.of(scanned);
        }
        return Optional.ofNullable(FALLBACK.get(entityType));
    }
}
