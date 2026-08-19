package com.foremen.service.permission.property;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5: Permission Field Masking
 *
 * For any model object and for any non-empty set of admin-only field names that exist on the model:
 * - When the caller does NOT have admin role: after maskAdminOnlyFields, each field in the admin-only set SHALL be null
 * - When the caller HAS admin role: after maskAdminOnlyFields, all fields on the model SHALL retain their original values
 * - Fields NOT in adminOnlyFields are never modified regardless of role
 *
 * <p><b>Validates: Requirements 8.2, 8.3</b></p>
 */
class PermissionMaskingPropertyTest {

    private static final List<String> ALL_FIELDS = List.of("field1", "field2", "field3", "field4");

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * When the caller is NOT admin: after masking, every field in the admin-only set SHALL be null.
     */
    @Property(tries = 100)
    void nonAdminCallerSeesNullForAdminOnlyFields(
            @ForAll("fieldValues") Map<String, String> fieldValues,
            @ForAll("adminOnlyFieldSubsets") Set<String> adminOnlyFields) {

        // Arrange: set non-admin security context
        setupSecurityContext(false);

        TestModel model = createModelFromValues(fieldValues);
        ReadOnlyAdminService<?, ?, ?, ?> service = createServiceWithAdminFields(adminOnlyFields);

        // Act
        service.maskAdminOnlyFields(model);

        // Assert: all admin-only fields are null
        for (String adminField : adminOnlyFields) {
            assertThat(getFieldValue(model, adminField))
                    .as("Admin-only field '%s' should be null for non-admin caller", adminField)
                    .isNull();
        }
    }

    /**
     * When the caller IS admin: after masking, all fields retain their original values unchanged.
     */
    @Property(tries = 100)
    void adminCallerSeesAllFieldsUnchanged(
            @ForAll("fieldValues") Map<String, String> fieldValues,
            @ForAll("adminOnlyFieldSubsets") Set<String> adminOnlyFields) {

        // Arrange: set admin security context
        setupSecurityContext(true);

        TestModel model = createModelFromValues(fieldValues);
        ReadOnlyAdminService<?, ?, ?, ?> service = createServiceWithAdminFields(adminOnlyFields);

        // Act
        service.maskAdminOnlyFields(model);

        // Assert: all fields retain their original values
        for (String field : ALL_FIELDS) {
            assertThat(getFieldValue(model, field))
                    .as("Field '%s' should retain original value for admin caller", field)
                    .isEqualTo(fieldValues.get(field));
        }
    }

    /**
     * Fields NOT in adminOnlyFields are never modified regardless of role.
     */
    @Property(tries = 100)
    void nonAdminFieldsAreNeverModified(
            @ForAll("fieldValues") Map<String, String> fieldValues,
            @ForAll("adminOnlyFieldSubsets") Set<String> adminOnlyFields,
            @ForAll boolean isAdmin) {

        // Arrange
        setupSecurityContext(isAdmin);

        TestModel model = createModelFromValues(fieldValues);
        ReadOnlyAdminService<?, ?, ?, ?> service = createServiceWithAdminFields(adminOnlyFields);

        // Act
        service.maskAdminOnlyFields(model);

        // Assert: non-admin fields retain original values regardless of role
        for (String field : ALL_FIELDS) {
            if (!adminOnlyFields.contains(field)) {
                assertThat(getFieldValue(model, field))
                        .as("Non-admin field '%s' should retain original value regardless of role", field)
                        .isEqualTo(fieldValues.get(field));
            }
        }
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<Map<String, String>> fieldValues() {
        Arbitrary<String> values = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20);

        return values.flatMap(v1 ->
                values.flatMap(v2 ->
                        values.flatMap(v3 ->
                                values.map(v4 -> {
                                    Map<String, String> map = new LinkedHashMap<>();
                                    map.put("field1", v1);
                                    map.put("field2", v2);
                                    map.put("field3", v3);
                                    map.put("field4", v4);
                                    return map;
                                })
                        )
                )
        );
    }

    @Provide
    Arbitrary<Set<String>> adminOnlyFieldSubsets() {
        return Arbitraries.of(ALL_FIELDS)
                .set()
                .ofMinSize(1)
                .ofMaxSize(4);
    }

    // --- Helpers ---

    private void setupSecurityContext(boolean isAdmin) {
        List<SimpleGrantedAuthority> authorities = isAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "testUser", null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private TestModel createModelFromValues(Map<String, String> values) {
        TestModel model = new TestModel();
        model.setField1(values.get("field1"));
        model.setField2(values.get("field2"));
        model.setField3(values.get("field3"));
        model.setField4(values.get("field4"));
        return model;
    }

    private String getFieldValue(TestModel model, String fieldName) {
        return switch (fieldName) {
            case "field1" -> model.getField1();
            case "field2" -> model.getField2();
            case "field3" -> model.getField3();
            case "field4" -> model.getField4();
            default -> throw new IllegalArgumentException("Unknown field: " + fieldName);
        };
    }

    private ReadOnlyAdminService<?, ?, ?, ?> createServiceWithAdminFields(Set<String> adminOnlyFields) {
        return new ReadOnlyAdminService<Object, Object, Object, Long>() {
            @Override
            public ServiceToDaoMapper<Object, Object, Object> getMapper() {
                return null;
            }

            @Override
            public ReadOnlyAdminDao<Object, Long> getReadDao() {
                return null;
            }

            @Override
            public EntityManager getEntityManager() {
                return null;
            }

            @Override
            public Set<String> getAdminOnlyFields() {
                return adminOnlyFields;
            }
        };
    }

    // --- Test POJO ---

    static class TestModel {
        private String field1;
        private String field2;
        private String field3;
        private String field4;

        public String getField1() { return field1; }
        public void setField1(String field1) { this.field1 = field1; }

        public String getField2() { return field2; }
        public void setField2(String field2) { this.field2 = field2; }

        public String getField3() { return field3; }
        public void setField3(String field3) { this.field3 = field3; }

        public String getField4() { return field4; }
        public void setField4(String field4) { this.field4 = field4; }
    }
}
