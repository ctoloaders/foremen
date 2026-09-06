package com.foremen.util;

import com.foremen.controller.model.MetadataResponse;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import net.jqwik.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based and unit tests for EntityMetadataResolver.
 * Validates: Requirements 10.2, 10.3, 10.4, 10.5, 10.8
 */
class EntityMetadataResolverTest {

    // --- Type Mapping Tests ---

    /**
     * Validates: Requirements 10.2, 10.3
     * String fields should be mapped to DataType.STRING.
     */
    @Test
    void resolveRoleEntity_mapsStringFieldsCorrectly() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        var codeField = result.fields().stream()
                .filter(f -> f.name().equals("code"))
                .findFirst();
        assertThat(codeField).isPresent();
        assertThat(codeField.get().dataType()).isEqualTo(MetadataResponse.DataType.STRING);
    }

    /**
     * Validates: Requirements 10.2, 10.3
     * Boolean fields should be mapped to DataType.BOOLEAN.
     */
    @Test
    void resolveRoleEntity_mapsBooleanCorrectly() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        var systemField = result.fields().stream()
                .filter(f -> f.name().equals("system"))
                .findFirst();
        assertThat(systemField).isPresent();
        assertThat(systemField.get().dataType()).isEqualTo(MetadataResponse.DataType.BOOLEAN);
    }

    /**
     * Validates: Requirements 10.2, 10.3
     * LocalDateTime fields should be mapped to DataType.DATE.
     */
    @Test
    void resolveRoleEntity_mapsDateTimeCorrectly() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        var createdDateField = result.fields().stream()
                .filter(f -> f.name().equals("createdDate"))
                .findFirst();
        assertThat(createdDateField).isPresent();
        assertThat(createdDateField.get().dataType()).isEqualTo(MetadataResponse.DataType.DATE);
    }

    /**
     * Validates: Requirements 10.2, 10.3
     * Long fields should be mapped to DataType.NUMBER.
     */
    @Test
    void resolveRoleEntity_mapsLongIdAsNumber() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        var idField = result.fields().stream()
                .filter(f -> f.name().equals("id"))
                .findFirst();
        assertThat(idField).isPresent();
        assertThat(idField.get().dataType()).isEqualTo(MetadataResponse.DataType.NUMBER);
    }

    // --- i18n Field Identification Tests ---

    /**
     * Validates: Requirements 10.2, 10.5
     * Fields with locale suffixes (RU, PL) should produce a single i18n=true base field.
     */
    @Test
    void resolveRoleEntity_identifiesI18nFields() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        // name should be i18n (nameRU and namePL exist)
        var nameField = result.fields().stream()
                .filter(f -> f.name().equals("name"))
                .findFirst();
        assertThat(nameField).isPresent();
        assertThat(nameField.get().i18n()).isTrue();

        // description should be i18n
        var descField = result.fields().stream()
                .filter(f -> f.name().equals("description"))
                .findFirst();
        assertThat(descField).isPresent();
        assertThat(descField.get().i18n()).isTrue();
    }

    /**
     * Validates: Requirements 10.2, 10.5
     * Locale-suffixed fields (nameRU, namePL, etc.) should NOT appear in the output.
     */
    @Test
    void resolveRoleEntity_skipsI18nSuffixedFields() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleEntity.class);

        List<String> fieldNames = result.fields().stream()
                .map(MetadataResponse.FieldInfo::name)
                .toList();

        assertThat(fieldNames).doesNotContain("nameRU", "namePL", "descriptionRU", "descriptionPL");
    }

    // --- Nested Field Traversal Tests ---

    /**
     * Validates: Requirements 10.2, 10.4
     * Fields annotated with @ManyToOne should produce nested metadata.
     */
    @Test
    void resolveRoleResourceEntity_traversesManyToOneFields() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleResourceEntity.class);

        // RoleResourceEntity has @ManyToOne RoleEntity role
        var roleField = result.fields().stream()
                .filter(f -> f.name().equals("role"))
                .findFirst();
        assertThat(roleField).isPresent();
        assertThat(roleField.get().nested()).isNotNull();
        assertThat(roleField.get().nested()).isNotEmpty();

        // The nested metadata should contain RoleEntity fields
        List<String> nestedFieldNames = roleField.get().nested().stream()
                .map(MetadataResponse.FieldInfo::name)
                .toList();
        assertThat(nestedFieldNames).contains("code", "name", "system");
    }

    /**
     * Validates: Requirements 10.2, 10.4
     * The resource field (also @ManyToOne) should be resolved with nested metadata.
     */
    @Test
    void resolveRoleResourceEntity_traversesResourceManyToOne() {
        MetadataResponse result = EntityMetadataResolver.resolve(RoleResourceEntity.class);

        var resourceField = result.fields().stream()
                .filter(f -> f.name().equals("resource"))
                .findFirst();
        assertThat(resourceField).isPresent();
        assertThat(resourceField.get().nested()).isNotNull();
        assertThat(resourceField.get().nested()).isNotEmpty();
    }

    // --- Cache Tests ---

    /**
     * Validates: Requirements 10.8
     * The cache should return the exact same instance on repeated calls.
     */
    @Test
    void resolve_cacheReturnsSameInstance() {
        MetadataResponse first = EntityMetadataResolver.resolve(RoleEntity.class);
        MetadataResponse second = EntityMetadataResolver.resolve(RoleEntity.class);

        // Should be the exact same reference (cached via ConcurrentHashMap.computeIfAbsent)
        assertThat(first).isSameAs(second);
    }

    // --- jqwik Property Tests ---

    /**
     * Validates: Requirements 10.2, 10.3
     * Property: For any known entity class, all resolved fields have non-null dataType and non-empty name.
     */
    @Property(tries = 10)
    void allFieldsHaveValidDataTypeAndName(@ForAll("entityClasses") Class<?> clazz) {
        MetadataResponse result = EntityMetadataResolver.resolve(clazz);

        for (MetadataResponse.FieldInfo field : result.fields()) {
            assertThat(field.dataType())
                    .as("Field '%s' must have a non-null dataType", field.name())
                    .isNotNull();
            assertThat(field.name())
                    .as("Field name must not be null or empty")
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    /**
     * Validates: Requirements 10.8
     * Property: Resolving the same class multiple times always returns the same cached instance.
     */
    @Property(tries = 10)
    void cacheIsConsistentForSameClass(@ForAll("entityClasses") Class<?> clazz) {
        MetadataResponse first = EntityMetadataResolver.resolve(clazz);
        MetadataResponse second = EntityMetadataResolver.resolve(clazz);

        assertThat(first).isSameAs(second);
    }

    /**
     * Validates: Requirements 10.2, 10.5
     * Property: No locale-suffixed field names (ending in RU or PL with a known base) appear in output.
     */
    @Property(tries = 10)
    void noLocaleSuffixedFieldsInOutput(@ForAll("entityClasses") Class<?> clazz) {
        MetadataResponse result = EntityMetadataResolver.resolve(clazz);

        List<String> fieldNames = result.fields().stream()
                .map(MetadataResponse.FieldInfo::name)
                .toList();

        // If an i18n base field exists, its suffixed variants should NOT be present
        for (MetadataResponse.FieldInfo field : result.fields()) {
            if (field.i18n()) {
                assertThat(fieldNames).doesNotContain(field.name() + "RU");
                assertThat(fieldNames).doesNotContain(field.name() + "PL");
            }
        }
    }

    /**
     * Validates: Requirements 10.2, 10.4
     * Property: Nested fields (from @ManyToOne/@OneToOne/@Embedded) themselves have valid structure.
     */
    @Property(tries = 10)
    void nestedFieldsHaveValidStructure(@ForAll("entityClassesWithNested") Class<?> clazz) {
        MetadataResponse result = EntityMetadataResolver.resolve(clazz);

        for (MetadataResponse.FieldInfo field : result.fields()) {
            if (field.nested() != null) {
                assertThat(field.nested()).isNotEmpty();
                for (MetadataResponse.FieldInfo nestedField : field.nested()) {
                    assertThat(nestedField.name()).isNotNull().isNotEmpty();
                    assertThat(nestedField.dataType()).isNotNull();
                }
            }
        }
    }

    // --- Reference Descriptor Tests (FOR-04-01) ---

    /**
     * Resets the static reference registry after each test so tests that inject a stub registry
     * (which also clears the metadata cache) do not leak state into other tests. Passing null both
     * restores the pre-feature "no reference descriptors" behavior and clears the cache.
     */
    @AfterEach
    void resetReferenceRegistry() {
        EntityMetadataResolver.setReferenceRegistry(null);
    }

    /**
     * Stub registry that resolves {@link RoleEntity} to the ROLES resource at {@code /api/roles}
     * without booting Spring. {@code afterSingletonsInstantiated()} is never invoked, so only the
     * overridden {@link #lookup(Class)} drives resolution.
     */
    private static ReferenceResourceRegistry roleStubRegistry() {
        return new ReferenceResourceRegistry(null) {
            @Override
            public Optional<Reference> lookup(Class<?> entityType) {
                if (entityType == RoleEntity.class) {
                    return Optional.of(new Reference("ROLES", "/api/roles"));
                }
                return Optional.empty();
            }
        };
    }

    /**
     * Validates: Requirements 1.1, 1.2, 1.3
     * A @ManyToOne field (UserEntity.role) yields a ReferenceInfo with idPath=role.id,
     * targetResource=ROLES / optionsPath=/api/roles (from the injected registry), and an i18n
     * label field of "name" (RoleEntity has nameRU/namePL).
     */
    @Test
    void resolveUserEntity_emitsReferenceInfoForRoleManyToOne() {
        EntityMetadataResolver.setReferenceRegistry(roleStubRegistry());

        MetadataResponse result = EntityMetadataResolver.resolve(UserEntity.class);

        var roleField = result.fields().stream()
                .filter(f -> f.name().equals("role"))
                .findFirst();
        assertThat(roleField).isPresent();

        MetadataResponse.ReferenceInfo reference = roleField.get().reference();
        assertThat(reference).isNotNull();
        assertThat(reference.idPath()).isEqualTo("role.id");
        assertThat(reference.targetResource()).isEqualTo("ROLES");
        assertThat(reference.optionsPath()).isEqualTo("/api/roles");
        assertThat(reference.labelField()).isEqualTo("name");
        assertThat(reference.labelI18n()).isTrue();
    }

    /**
     * Validates: Requirements 1.4
     * A scalar field (UserEntity.email) yields no reference descriptor (reference == null).
     */
    @Test
    void resolveUserEntity_scalarFieldHasNoReference() {
        EntityMetadataResolver.setReferenceRegistry(roleStubRegistry());

        MetadataResponse result = EntityMetadataResolver.resolve(UserEntity.class);

        var emailField = result.fields().stream()
                .filter(f -> f.name().equals("email"))
                .findFirst();
        assertThat(emailField).isPresent();
        assertThat(emailField.get().reference()).isNull();
    }

    /**
     * Validates: Requirements 1.4, 1.5
     * Backward compatibility: existing FieldInfo shape (name/dataType/i18n) is unchanged for both
     * scalar and reference fields; scalar fields keep reference == null. The reference field still
     * carries its dataType and its nested metadata alongside the new descriptor.
     */
    @Test
    void resolveUserEntity_preservesExistingFieldInfoShape() {
        EntityMetadataResolver.setReferenceRegistry(roleStubRegistry());

        MetadataResponse result = EntityMetadataResolver.resolve(UserEntity.class);

        // Scalar STRING field unchanged
        var nameField = result.fields().stream()
                .filter(f -> f.name().equals("name"))
                .findFirst();
        assertThat(nameField).isPresent();
        assertThat(nameField.get().dataType()).isEqualTo(MetadataResponse.DataType.STRING);
        assertThat(nameField.get().i18n()).isFalse();
        assertThat(nameField.get().nested()).isNull();
        assertThat(nameField.get().reference()).isNull();

        // Enum field unchanged
        var statusField = result.fields().stream()
                .filter(f -> f.name().equals("status"))
                .findFirst();
        assertThat(statusField).isPresent();
        assertThat(statusField.get().dataType()).isEqualTo(MetadataResponse.DataType.ENUM);
        assertThat(statusField.get().reference()).isNull();

        // Reference field still exposes its nested metadata (existing behavior) plus the descriptor
        var roleField = result.fields().stream()
                .filter(f -> f.name().equals("role"))
                .findFirst();
        assertThat(roleField).isPresent();
        assertThat(roleField.get().nested()).isNotNull().isNotEmpty();
    }

    /**
     * Validates: Requirements 1.1
     * Without a registry (plain unit context), a reference field still emits a descriptor with the
     * id path and label field, but targetResource/optionsPath are null (no options endpoint known).
     */
    @Test
    void resolveUserEntity_withoutRegistry_emitsReferenceWithoutTargetResource() {
        // No registry set (reset in @AfterEach guarantees null baseline)
        MetadataResponse result = EntityMetadataResolver.resolve(UserEntity.class);

        var roleField = result.fields().stream()
                .filter(f -> f.name().equals("role"))
                .findFirst();
        assertThat(roleField).isPresent();

        MetadataResponse.ReferenceInfo reference = roleField.get().reference();
        assertThat(reference).isNotNull();
        assertThat(reference.idPath()).isEqualTo("role.id");
        assertThat(reference.labelField()).isEqualTo("name");
        assertThat(reference.labelI18n()).isTrue();
        assertThat(reference.targetResource()).isNull();
        assertThat(reference.optionsPath()).isNull();
    }

    // --- Providers ---

    @Provide
    Arbitrary<Class<?>> entityClasses() {
        return Arbitraries.of(RoleEntity.class, RoleResourceEntity.class);
    }

    @Provide
    Arbitrary<Class<?>> entityClassesWithNested() {
        // RoleResourceEntity has @ManyToOne fields -> nested metadata
        return Arbitraries.of(RoleResourceEntity.class);
    }
}
