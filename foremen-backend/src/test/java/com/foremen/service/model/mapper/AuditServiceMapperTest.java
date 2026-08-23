package com.foremen.service.model.mapper;

import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.AuditServiceExtendedModel;
import com.foremen.service.model.AuditServiceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.MappingTarget;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AuditServiceMapper default methods.
 * Validates: Requirements 2.4, 2.5
 */
class AuditServiceMapperTest {

    /**
     * Stub implementation — only default methods (jsonStringToMap, getI18nSupportedProperties) are under test.
     */
    private final AuditServiceMapper mapper = new AuditServiceMapper() {
        @Override
        public AuditServiceModel toServiceModel(AuditLogEntity entity) {
            return null;
        }

        @Override
        public AuditServiceExtendedModel toServiceExtendedModel(AuditLogEntity entity) {
            return null;
        }

        @Override
        public AuditLogEntity toCreateDaoModel(AuditServiceExtendedModel source) {
            return null;
        }

        @Override
        public void updateFields(AuditServiceExtendedModel source, @MappingTarget AuditLogEntity target) {
        }
    };

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
}
