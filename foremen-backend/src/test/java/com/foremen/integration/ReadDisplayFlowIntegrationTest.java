package com.foremen.integration;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.controller.model.mapper.TestEntityControllerMapperImpl;
import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestDtoModel;
import com.foremen.mapper.fixture.TestServiceModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import com.foremen.service.model.mapper.TestEntityServiceMapperImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration test for the read display flow:
 * DaoModel → ServiceModel (locale-resolved via processI18n) → DtoModel (via ControllerMapper toDto).
 *
 * Validates: Requirements 4.2, 4.4, 4.5, 4.6
 */
class ReadDisplayFlowIntegrationTest {

    private final TestEntityServiceMapper serviceMapper = new TestEntityServiceMapperImpl();
    private final TestEntityControllerMapper controllerMapper = new TestEntityControllerMapperImpl();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void ruLocaleResolvesRussianFields() {
        // Given
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

        TestDaoModel dao = new TestDaoModel();
        dao.setId(1L);
        dao.setNameRU("Русское имя");
        dao.setNamePL("Polska nazwa");
        dao.setDescriptionRU("Русское описание");
        dao.setDescriptionPL("Opis polski");
        dao.setCode("TEST-001");
        dao.setQuantity(42);

        // When
        TestServiceModel serviceModel = serviceMapper.toServiceModel(dao);
        TestDtoModel dtoModel = controllerMapper.toDto(serviceModel);

        // Then
        assertEquals("Русское имя", dtoModel.getName(),
                "RU locale should resolve name from dao.nameRU");
        assertEquals("Русское описание", dtoModel.getDescription(),
                "RU locale should resolve description from dao.descriptionRU");
    }

    @Test
    void plLocaleResolvesPolishFields() {
        // Given
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pl"));

        TestDaoModel dao = new TestDaoModel();
        dao.setId(2L);
        dao.setNameRU("Русское имя");
        dao.setNamePL("Polska nazwa");
        dao.setDescriptionRU("Русское описание");
        dao.setDescriptionPL("Opis polski");
        dao.setCode("TEST-002");
        dao.setQuantity(99);

        // When
        TestServiceModel serviceModel = serviceMapper.toServiceModel(dao);
        TestDtoModel dtoModel = controllerMapper.toDto(serviceModel);

        // Then
        assertEquals("Polska nazwa", dtoModel.getName(),
                "PL locale should resolve name from dao.namePL");
        assertEquals("Opis polski", dtoModel.getDescription(),
                "PL locale should resolve description from dao.descriptionPL");
    }

    @Test
    void nonI18nFieldsPropagatedUnchanged() {
        // Given
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

        TestDaoModel dao = new TestDaoModel();
        dao.setId(99L);
        dao.setNameRU("Имя");
        dao.setNamePL("Nazwa");
        dao.setDescriptionRU("Описание");
        dao.setDescriptionPL("Opis");
        dao.setCode("NON-I18N-CODE");
        dao.setQuantity(777);

        // When
        TestServiceModel serviceModel = serviceMapper.toServiceModel(dao);
        TestDtoModel dtoModel = controllerMapper.toDto(serviceModel);

        // Then
        assertEquals("NON-I18N-CODE", dtoModel.getCode(),
                "code should pass through unchanged regardless of locale");
        assertEquals(Integer.valueOf(777), dtoModel.getQuantity(),
                "quantity should pass through unchanged regardless of locale");
        assertEquals(Long.valueOf(99L), dtoModel.getId(),
                "id should pass through unchanged regardless of locale");
    }

    @Test
    void nullI18nFieldsResolveToNull() {
        // Given
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

        TestDaoModel dao = new TestDaoModel();
        dao.setId(3L);
        dao.setNameRU(null); // null RU name
        dao.setNamePL("Polska nazwa");
        dao.setDescriptionRU(null); // null RU description
        dao.setDescriptionPL("Opis polski");
        dao.setCode("NULL-TEST");
        dao.setQuantity(10);

        // When
        TestServiceModel serviceModel = serviceMapper.toServiceModel(dao);
        TestDtoModel dtoModel = controllerMapper.toDto(serviceModel);

        // Then
        assertNull(dtoModel.getName(),
                "If dao.nameRU is null and locale is RU, DtoModel.name should be null");
        assertNull(dtoModel.getDescription(),
                "If dao.descriptionRU is null and locale is RU, DtoModel.description should be null");
    }
}
