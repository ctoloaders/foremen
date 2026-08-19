package com.foremen.integration;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.mapper.fixture.TestUpdateRequest;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration test for the full update flow:
 * UpdateRequest → ServiceExtendedModel (via ControllerMapper)
 * → updateFields(serviceExtendedModel, existingDao) (via ServiceMapper)
 *
 * Verifies partial update semantics, blank field normalization, and id preservation.
 *
 * Validates: Requirements 6.1, 6.2, 6.5
 */
class UpdateFlowIntegrationTest {

    private TestEntityControllerMapper controllerMapper;
    private TestEntityServiceMapper serviceMapper;

    @BeforeEach
    void setUp() {
        controllerMapper = Mappers.getMapper(TestEntityControllerMapper.class);
        serviceMapper = Mappers.getMapper(TestEntityServiceMapper.class);
    }

    @Test
    @DisplayName("Partial update preserves null fields on existing DAO")
    void partialUpdatePreservesNullFields() {
        // Given: an update request with some fields null (partial update)
        TestUpdateRequest updateRequest = new TestUpdateRequest();
        updateRequest.setId(99L);
        updateRequest.setNameRU("Новое имя");
        updateRequest.setNamePL(null);          // should not overwrite
        updateRequest.setDescriptionRU(null);   // should not overwrite
        updateRequest.setDescriptionPL(null);   // should not overwrite
        updateRequest.setCode(null);            // should not overwrite
        updateRequest.setQuantity(null);        // should not overwrite

        // Given: an existing DAO model with all fields populated
        TestDaoModel existingDao = new TestDaoModel();
        existingDao.setId(1L);
        existingDao.setNameRU("Старое имя");
        existingDao.setNamePL("Stara nazwa");
        existingDao.setDescriptionRU("Старое описание");
        existingDao.setDescriptionPL("Stary opis");
        existingDao.setCode("OLD-CODE");
        existingDao.setQuantity(42);

        // When: full update flow
        TestServiceExtendedModel serviceModel = controllerMapper.toUpdateServiceExtendedModel(updateRequest);
        serviceMapper.updateFields(serviceModel, existingDao);

        // Then: non-null source fields overwrite target
        assertEquals("Новое имя", existingDao.getNameRU());

        // Then: null source fields leave target unchanged
        assertEquals("Stara nazwa", existingDao.getNamePL());
        assertEquals("Старое описание", existingDao.getDescriptionRU());
        assertEquals("Stary opis", existingDao.getDescriptionPL());
        assertEquals("OLD-CODE", existingDao.getCode());
        assertEquals(42, existingDao.getQuantity());
    }

    @Test
    @DisplayName("Blank i18n field normalization after update (processI18nEmptyValues)")
    void blankFieldNormalizationAfterUpdate() {
        // Given: an update request with a blank i18n field
        TestUpdateRequest updateRequest = new TestUpdateRequest();
        updateRequest.setId(5L);
        updateRequest.setNameRU("  ");          // blank → should be normalized to null
        updateRequest.setNamePL("Valid name");
        updateRequest.setDescriptionRU("\t\n");  // blank → normalized to null
        updateRequest.setDescriptionPL("Valid opis");
        updateRequest.setCode("CODE-1");
        updateRequest.setQuantity(10);

        // Given: existing DAO
        TestDaoModel existingDao = new TestDaoModel();
        existingDao.setId(2L);
        existingDao.setNameRU("Original RU");
        existingDao.setNamePL("Original PL");
        existingDao.setDescriptionRU("Original Desc RU");
        existingDao.setDescriptionPL("Original Desc PL");
        existingDao.setCode("OLD");
        existingDao.setQuantity(5);

        // When: full update flow (processI18nEmptyValues is called as @AfterMapping)
        TestServiceExtendedModel serviceModel = controllerMapper.toUpdateServiceExtendedModel(updateRequest);
        serviceMapper.updateFields(serviceModel, existingDao);

        // Then: blank i18n fields are normalized to null
        assertNull(existingDao.getNameRU(), "Blank nameRU should be normalized to null");
        assertNull(existingDao.getDescriptionRU(), "Blank descriptionRU should be normalized to null");

        // Then: non-blank fields are preserved as-is
        assertEquals("Valid name", existingDao.getNamePL());
        assertEquals("Valid opis", existingDao.getDescriptionPL());
        assertEquals("CODE-1", existingDao.getCode());
        assertEquals(10, existingDao.getQuantity());
    }

    @Test
    @DisplayName("Id is preserved during update regardless of UpdateRequest id value")
    void idPreservedDuringUpdate() {
        // Given: update request with an id value (should NOT affect existing DAO id)
        TestUpdateRequest updateRequest = new TestUpdateRequest();
        updateRequest.setId(999L);
        updateRequest.setNameRU("Updated");
        updateRequest.setCode("NEW-CODE");

        // Given: existing DAO with its own id
        TestDaoModel existingDao = new TestDaoModel();
        existingDao.setId(1L);
        existingDao.setNameRU("Old name");
        existingDao.setNamePL("Old PL");
        existingDao.setDescriptionRU("Desc RU");
        existingDao.setDescriptionPL("Desc PL");
        existingDao.setCode("OLD-CODE");
        existingDao.setQuantity(7);

        // When: full update flow
        TestServiceExtendedModel serviceModel = controllerMapper.toUpdateServiceExtendedModel(updateRequest);
        serviceMapper.updateFields(serviceModel, existingDao);

        // Then: existing DAO id is never changed
        assertEquals(1L, existingDao.getId(), "DAO id must never be modified by update");

        // Then: other non-null fields are updated
        assertEquals("Updated", existingDao.getNameRU());
        assertEquals("NEW-CODE", existingDao.getCode());
    }

    @Test
    @DisplayName("Full overwrite scenario - all fields non-null in UpdateRequest")
    void fullOverwriteScenario() {
        // Given: update request with ALL fields non-null
        TestUpdateRequest updateRequest = new TestUpdateRequest();
        updateRequest.setId(50L);
        updateRequest.setNameRU("Полное имя");
        updateRequest.setNamePL("Pełna nazwa");
        updateRequest.setDescriptionRU("Полное описание");
        updateRequest.setDescriptionPL("Pełny opis");
        updateRequest.setCode("FULL-CODE");
        updateRequest.setQuantity(100);

        // Given: existing DAO with different values
        TestDaoModel existingDao = new TestDaoModel();
        existingDao.setId(3L);
        existingDao.setNameRU("Old RU");
        existingDao.setNamePL("Old PL");
        existingDao.setDescriptionRU("Old Desc RU");
        existingDao.setDescriptionPL("Old Desc PL");
        existingDao.setCode("OLD");
        existingDao.setQuantity(1);

        // When: full update flow
        TestServiceExtendedModel serviceModel = controllerMapper.toUpdateServiceExtendedModel(updateRequest);
        serviceMapper.updateFields(serviceModel, existingDao);

        // Then: ALL target fields are updated to new values
        assertEquals("Полное имя", existingDao.getNameRU());
        assertEquals("Pełna nazwa", existingDao.getNamePL());
        assertEquals("Полное описание", existingDao.getDescriptionRU());
        assertEquals("Pełny opis", existingDao.getDescriptionPL());
        assertEquals("FULL-CODE", existingDao.getCode());
        assertEquals(100, existingDao.getQuantity());

        // Then: id is still preserved
        assertEquals(3L, existingDao.getId(), "DAO id must never be modified by update");
    }
}
