package com.foremen.util;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.AdminController;
import com.foremen.controller.AdminReadOnlyController;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.util.ReferenceResourceRegistry.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferenceResourceRegistry}.
 *
 * <p>Uses a real Spring {@code AnnotationConfigApplicationContext} seeded with stub controller
 * beans so the actual bean-scan + {@code @PermissionResource}/{@code @RequestMapping} reading +
 * entity-type resolution path is exercised end to end (no mocking of the container).
 *
 * <p>Validates Requirement 1.1: an entity type resolves to its resource code and base path so the
 * metadata layer can emit a reference descriptor.
 */
@DisplayName("ReferenceResourceRegistry")
class ReferenceResourceRegistryTest {

    // --- Stub entity types standing in for JPA entities ---
    static class StubRoleEntity {}
    static class StubResourceEntity {}
    static class UnmappedEntity {}

    /**
     * Builds a registry from the given controller beans by registering them into a Spring context,
     * then running the registry's {@code afterSingletonsInstantiated} scan.
     */
    private ReferenceResourceRegistry buildRegistry(Object... controllerBeans) {
        var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        int i = 0;
        for (Object bean : controllerBeans) {
            ctx.getBeanFactory().registerSingleton("controller" + (i++), bean);
        }
        ctx.refresh();
        ReferenceResourceRegistry registry = new ReferenceResourceRegistry(ctx);
        registry.afterSingletonsInstantiated();
        return registry;
    }

    @Test
    @DisplayName("maps a full-CRUD controller's entity to its resource code and base path")
    void mapsAdminControllerEntity() {
        ReferenceResourceRegistry registry = buildRegistry(new StubRoleController());

        Optional<Reference> ref = registry.lookup(StubRoleEntity.class);

        assertThat(ref).isPresent();
        assertThat(ref.get().resourceCode()).isEqualTo("ROLES");
        assertThat(ref.get().basePath()).isEqualTo("/api/roles");
    }

    @Test
    @DisplayName("maps a read-only controller's entity to its resource code and base path")
    void mapsReadOnlyControllerEntity() {
        ReferenceResourceRegistry registry = buildRegistry(new StubResourceController());

        Optional<Reference> ref = registry.lookup(StubResourceEntity.class);

        assertThat(ref).isPresent();
        assertThat(ref.get().resourceCode()).isEqualTo("RESOURCES");
        assertThat(ref.get().basePath()).isEqualTo("/api/resources");
    }

    @Test
    @DisplayName("returns empty for an entity no controller exposes")
    void returnsEmptyForUnmappedEntity() {
        ReferenceResourceRegistry registry = buildRegistry(new StubRoleController());

        assertThat(registry.lookup(UnmappedEntity.class)).isEmpty();
    }

    @Test
    @DisplayName("returns empty for a null entity type")
    void returnsEmptyForNullType() {
        ReferenceResourceRegistry registry = buildRegistry();

        assertThat(registry.lookup(null)).isEmpty();
    }

    @Test
    @DisplayName("skips a controller without @PermissionResource")
    void skipsControllerWithoutPermissionResource() {
        ReferenceResourceRegistry registry = buildRegistry(new UnguardedController());

        assertThat(registry.lookup(StubResourceEntity.class)).isEmpty();
    }

    // --- Stub controllers ---

    @RequestMapping("/api/roles")
    @PermissionResource("ROLES")
    static class StubRoleController implements AdminController<
            Object, Object, Object, Object, StubRoleEntity, Long,
            Object, Object, Object, Object> {

        @Override
        @SuppressWarnings("unchecked")
        public ControllerToServiceMapper<Object, Object, Object, Object, Object, Object, Object, Object> getMapper() {
            return mock(ControllerToServiceMapper.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public AdminService<Object, Object, StubRoleEntity, Long> getService() {
            AdminService<Object, Object, StubRoleEntity, Long> service = mock(AdminService.class);
            when(service.getDaoModelClass()).thenReturn(StubRoleEntity.class);
            return service;
        }
    }

    @RequestMapping("/api/resources")
    @PermissionResource("RESOURCES")
    static class StubResourceController implements AdminReadOnlyController<
            Object, Object, StubResourceEntity, Long> {

        @Override
        @SuppressWarnings("unchecked")
        public ReadOnlyAdminService<Object, Object, StubResourceEntity, Long> getService() {
            ReadOnlyAdminService<Object, Object, StubResourceEntity, Long> service = mock(ReadOnlyAdminService.class);
            when(service.getDaoModelClass()).thenReturn(StubResourceEntity.class);
            return service;
        }
    }

    /** A read-only controller with a base path but no {@code @PermissionResource} — must be skipped. */
    @RequestMapping("/api/unguarded")
    static class UnguardedController implements AdminReadOnlyController<
            Object, Object, StubResourceEntity, Long> {

        @Override
        @SuppressWarnings("unchecked")
        public ReadOnlyAdminService<Object, Object, StubResourceEntity, Long> getService() {
            ReadOnlyAdminService<Object, Object, StubResourceEntity, Long> service = mock(ReadOnlyAdminService.class);
            when(service.getDaoModelClass()).thenReturn(StubResourceEntity.class);
            return service;
        }
    }
}
