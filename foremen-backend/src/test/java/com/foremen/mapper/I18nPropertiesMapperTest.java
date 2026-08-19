package com.foremen.mapper;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for I18nPropertiesMapper edge cases.
 * Validates: Requirements 4.3, 4.4, 4.7, 4.9
 */
class I18nPropertiesMapperTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("processI18n with non-existent field name throws IllegalArgumentException")
    void processI18n_nonExistentField_throwsIllegalArgumentException() {
        // A mapper that references a non-existent property name
        I18nPropertiesMapper<TestServiceModel, TestDaoModel> badMapper = new I18nPropertiesMapper<>() {
            @Override
            public Set<String> getI18nSupportedProperties() {
                return Set.of("nonExistentField");
            }
        };

        TestDaoModel source = new TestDaoModel();
        source.setNameRU("Тест");
        source.setNamePL("Test");

        TestServiceModel target = new TestServiceModel();

        // The source doesn't have "nonExistentFieldRU" or "nonExistentFieldPL" → reflection fails
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> badMapper.processI18n(target, source)
        );

        assertTrue(ex.getMessage().contains("nonExistentField"),
                "Exception message should reference the missing field name");
    }

    @Test
    @DisplayName("getInCurrentLocale defaults to PL suffix when locale is not Russian")
    void getInCurrentLocale_nonRussianLocale_defaultsToPL() {
        I18nPropertiesMapper<TestServiceModel, TestDaoModel> mapper = new I18nPropertiesMapper<>() {
            @Override
            public Set<String> getI18nSupportedProperties() {
                return Set.of("name");
            }
        };

        TestDaoModel source = new TestDaoModel();
        source.setNameRU("Русское имя");
        source.setNamePL("Polska nazwa");

        // Set locale to English (non-RU) — should default to PL
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Object result = mapper.getInCurrentLocale("name", source);
        assertEquals("Polska nazwa", result,
                "Non-Russian locale should resolve to PL suffix");
    }

    @Test
    @DisplayName("getInCurrentLocale defaults to PL when default JVM locale is used (non-RU)")
    void getInCurrentLocale_defaultJvmLocale_defaultsToPL() {
        I18nPropertiesMapper<TestServiceModel, TestDaoModel> mapper = new I18nPropertiesMapper<>() {
            @Override
            public Set<String> getI18nSupportedProperties() {
                return Set.of("name");
            }
        };

        TestDaoModel source = new TestDaoModel();
        source.setNameRU("Русское имя");
        source.setNamePL("Polska nazwa");

        // Reset locale context — LocaleContextHolder returns JVM default (typically not RU)
        LocaleContextHolder.resetLocaleContext();

        Object result = mapper.getInCurrentLocale("name", source);

        // When locale is not RU, it should default to PL
        Locale currentLocale = LocaleContextHolder.getLocale();
        if ("ru".equalsIgnoreCase(currentLocale.getLanguage())) {
            assertEquals("Русское имя", result);
        } else {
            assertEquals("Polska nazwa", result,
                    "When locale is not RU, should default to PL suffix");
        }
    }

    @Test
    @DisplayName("processI18n with empty getI18nSupportedProperties() makes no modifications")
    void processI18n_emptyProperties_noModifications() {
        I18nPropertiesMapper<TestServiceModel, TestDaoModel> emptyMapper = new I18nPropertiesMapper<>() {
            @Override
            public Set<String> getI18nSupportedProperties() {
                return Set.of();
            }
        };

        TestDaoModel source = new TestDaoModel();
        source.setNameRU("Тест");
        source.setNamePL("Test");
        source.setDescriptionRU("Описание");
        source.setDescriptionPL("Opis");

        TestServiceModel target = new TestServiceModel();
        target.setName("original");
        target.setDescription("original desc");

        // processI18n should return early without modifying the target
        emptyMapper.processI18n(target, source);

        assertEquals("original", target.getName(),
                "Target name should remain unchanged with empty properties set");
        assertEquals("original desc", target.getDescription(),
                "Target description should remain unchanged with empty properties set");
    }
}
