package com.foremen.integration;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestDtoExtendedModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration test for the read full flow (no locale resolution):
 * DaoModel → ServiceExtendedModel (via TestEntityServiceMapper.toServiceExtendedModel)
 * → ExtendedDTO (via TestEntityControllerMapper.toExtendedDto)
 *
 * Validates: Requirements 2.4, 3.5
 */
class ReadFullFlowIntegrationTest {

    private final TestEntityServiceMapper serviceMapper = Mappers.getMapper(TestEntityServiceMapper.class);
    private final TestEntityControllerMapper controllerMapper = Mappers.getMapper(TestEntityControllerMapper.class);

    @BeforeEach
    void setUp() {
        LocaleContextHolder.resetLocaleContext();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void allI18nFieldsPassThroughUnchanged() {
        TestDaoModel dao = createDao();

        TestServiceExtendedModel extModel = serviceMapper.toServiceExtendedModel(dao);
        TestDtoExtendedModel dto = controllerMapper.toExtendedDto(extModel);

        assertEquals("Название RU", dto.getNameRU());
        assertEquals("Nazwa PL", dto.getNamePL());
        assertEquals("Описание RU", dto.getDescriptionRU());
        assertEquals("Opis PL", dto.getDescriptionPL());
    }

    @Test
    void localeIndependence() {
        TestDaoModel dao = createDao();

        // Perform flow with RU locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        TestServiceExtendedModel extModelRU = serviceMapper.toServiceExtendedModel(dao);
        TestDtoExtendedModel dtoRU = controllerMapper.toExtendedDto(extModelRU);

        // Perform flow with PL locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pl"));
        TestServiceExtendedModel extModelPL = serviceMapper.toServiceExtendedModel(dao);
        TestDtoExtendedModel dtoPL = controllerMapper.toExtendedDto(extModelPL);

        // Results must be identical — locale has no effect on toServiceExtendedModel path
        assertEquals(dtoRU.getNameRU(), dtoPL.getNameRU());
        assertEquals(dtoRU.getNamePL(), dtoPL.getNamePL());
        assertEquals(dtoRU.getDescriptionRU(), dtoPL.getDescriptionRU());
        assertEquals(dtoRU.getDescriptionPL(), dtoPL.getDescriptionPL());
        assertEquals(dtoRU.getId(), dtoPL.getId());
        assertEquals(dtoRU.getCode(), dtoPL.getCode());
        assertEquals(dtoRU.getQuantity(), dtoPL.getQuantity());
    }

    @Test
    void nonI18nFieldsPropagated() {
        TestDaoModel dao = createDao();

        TestServiceExtendedModel extModel = serviceMapper.toServiceExtendedModel(dao);
        TestDtoExtendedModel dto = controllerMapper.toExtendedDto(extModel);

        assertEquals(42L, dto.getId());
        assertEquals("CODE-001", dto.getCode());
        assertEquals(99, dto.getQuantity());
    }

    @Test
    void nullFieldsPropagated() {
        TestDaoModel dao = new TestDaoModel();
        dao.setId(1L);
        dao.setNameRU(null);
        dao.setNamePL(null);
        dao.setDescriptionRU(null);
        dao.setDescriptionPL(null);
        dao.setCode(null);
        dao.setQuantity(null);

        TestServiceExtendedModel extModel = serviceMapper.toServiceExtendedModel(dao);
        TestDtoExtendedModel dto = controllerMapper.toExtendedDto(extModel);

        assertNull(dto.getNameRU());
        assertNull(dto.getNamePL());
        assertNull(dto.getDescriptionRU());
        assertNull(dto.getDescriptionPL());
    }

    private TestDaoModel createDao() {
        TestDaoModel dao = new TestDaoModel();
        dao.setId(42L);
        dao.setNameRU("Название RU");
        dao.setNamePL("Nazwa PL");
        dao.setDescriptionRU("Описание RU");
        dao.setDescriptionPL("Opis PL");
        dao.setCode("CODE-001");
        dao.setQuantity(99);
        return dao;
    }
}
