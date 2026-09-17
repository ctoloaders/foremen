package com.foremen.service.model.mapper;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.audit.AuditPerformedByResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AuditServiceMapper.
 * Covers the JSON snapshot helper, the i18n property set, and the read-time
 * {@code performedBy} id→name resolution against a mocked {@link UserDao}.
 * Validates: Requirements 2.4, 2.5
 */
class AuditServiceMapperTest {

    // Use the MapStruct-generated implementation (the mapper is now an abstract class).
    // The id→name resolution lives in AuditPerformedByResolver, which the mapper delegates to; we
    // build a real resolver over a mocked UserDao and inject it into the mapper's protected
    // performedByResolver field via reflection.
    private final AuditServiceMapper mapper = new AuditServiceMapperImpl();
    private final UserDao userDao = mock(UserDao.class);
    private final AuditPerformedByResolver performedByResolver = new AuditPerformedByResolver(userDao);

    @BeforeEach
    void injectResolver() throws Exception {
        Field field = AuditServiceMapper.class.getDeclaredField("performedByResolver");
        field.setAccessible(true);
        field.set(mapper, performedByResolver);
    }

    private UserEntity user(String name) {
        UserEntity u = new UserEntity();
        u.setName(name);
        return u;
    }

    @Test
    @DisplayName("jsonStringToMap: valid JSON returns correct Map")
    void jsonStringToMap_validJson_returnsCorrectMap() {
        String json = "{\"name\":\"Test\",\"value\":42,\"active\":true}";

        Map<String, Object> result = mapper.jsonStringToMap(json);

        assertThat(result)
                .isNotNull()
                .containsEntry("name", "Test")
                .containsEntry("value", 42)
                .containsEntry("active", true);
    }

    @Test
    @DisplayName("jsonStringToMap: null input returns null")
    void jsonStringToMap_null_returnsNull() {
        Map<String, Object> result = mapper.jsonStringToMap(null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("jsonStringToMap: blank string returns null")
    void jsonStringToMap_blankString_returnsNull() {
        Map<String, Object> result = mapper.jsonStringToMap("   ");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("jsonStringToMap: invalid JSON returns Map with _raw key")
    void jsonStringToMap_invalidJson_returnsMapWithRawKey() {
        String invalidJson = "not-valid-json{{{";

        Map<String, Object> result = mapper.jsonStringToMap(invalidJson);

        assertThat(result)
                .isNotNull()
                .hasSize(1)
                .containsEntry("_raw", invalidJson);
    }

    @Test
    @DisplayName("getI18nSupportedProperties returns empty Set")
    void getI18nSupportedProperties_returnsEmptySet() {
        Set<String> properties = mapper.getI18nSupportedProperties();

        assertThat(properties)
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("toServiceModel: numeric performedBy resolving to a user maps to that user's name")
    void toServiceModel_numericIdWithUser_resolvesToName() {
        when(userDao.findById(42L)).thenReturn(Optional.of(user("Иван Петров")));

        AuditLogEntity entity = auditEntity("42");

        assertThat(mapper.toServiceModel(entity).performedBy()).isEqualTo("Иван Петров");
    }

    @Test
    @DisplayName("toServiceExtendedModel: numeric performedBy resolving to a user maps to that user's name")
    void toServiceExtendedModel_numericIdWithUser_resolvesToName() {
        when(userDao.findById(7L)).thenReturn(Optional.of(user("Anna Kowalska")));

        AuditLogEntity entity = auditEntity("7");

        assertThat(mapper.toServiceExtendedModel(entity).performedBy()).isEqualTo("Anna Kowalska");
    }

    @Test
    @DisplayName("performedBy: numeric id with no matching user returns the id string unchanged")
    void toServiceModel_numericIdWithoutUser_returnsIdUnchanged() {
        when(userDao.findById(99L)).thenReturn(Optional.empty());

        AuditLogEntity entity = auditEntity("99");

        assertThat(mapper.toServiceModel(entity).performedBy()).isEqualTo("99");
    }

    @Test
    @DisplayName("performedBy: non-numeric value (SYSTEM) is passed through and never queried")
    void toServiceModel_nonNumeric_passthrough() {
        AuditLogEntity entity = auditEntity("SYSTEM");

        assertThat(mapper.toServiceModel(entity).performedBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("performedBy: null is passed through")
    void toServiceModel_null_passthrough() {
        AuditLogEntity entity = auditEntity(null);

        assertThat(mapper.toServiceModel(entity).performedBy()).isNull();
    }

    private AuditLogEntity auditEntity(String performedBy) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setEntityClass("com.foremen.dao.model.UserEntity");
        entity.setEntityId(1L);
        entity.setOperation("CREATE");
        entity.setPerformedBy(performedBy);
        return entity;
    }
}
