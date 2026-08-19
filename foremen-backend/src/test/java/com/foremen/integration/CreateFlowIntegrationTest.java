package com.foremen.integration;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.mapper.fixture.TestCreateRequest;
import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full create flow:
 * CreateRequest → ServiceExtendedModel (ControllerMapper) → DaoModel (ServiceMapper)
 *
 * Validates: Requirements 2.5, 2.10, 3.2
 */
class CreateFlowIntegrationTest {

    private TestEntityControllerMapper controllerMapper;
    private TestEntityServiceMapper serviceMapper;

    @BeforeEach
    void setUp() {
        controllerMapper = Mappers.getMapper(TestEntityControllerMapper.class);
        serviceMapper = Mappers.getMapper(TestEntityServiceMapper.class);
    }

    @Test
    @DisplayName("Normal create flow: i18n fields propagate through both mappers, id is null at each step")
    void normalCreateFlow_fieldsPropagate_idNull() {
        // Given: a create request with non-blank i18n fields
        TestCreateRequest request = new TestCreateRequest();
        request.setNameRU("Название");
        request.setNamePL("Nazwa");
        request.setDescriptionRU("Описание на русском");
        request.setDescriptionPL("Opis po polsku");
        request.setCode("TEST-001");
        request.setQuantity(42);

        // When: flow through controller mapper
        TestServiceExtendedModel extModel = controllerMapper.toServiceExtendedModel(request);

        // Then: id is null on the extended model (ignored by @Mapping)
        assertThat(extModel.getId()).isNull();
        // And: i18n fields propagated correctly
        assertThat(extModel.getNameRU()).isEqualTo("Название");
        assertThat(extModel.getNamePL()).isEqualTo("Nazwa");
        assertThat(extModel.getDescriptionRU()).isEqualTo("Описание на русском");
        assertThat(extModel.getDescriptionPL()).isEqualTo("Opis po polsku");
        assertThat(extModel.getCode()).isEqualTo("TEST-001");
        assertThat(extModel.getQuantity()).isEqualTo(42);

        // When: flow through service mapper
        TestDaoModel daoModel = serviceMapper.toCreateDaoModel(extModel);

        // Then: id is null on DAO model (ignored by @Mapping)
        assertThat(daoModel.getId()).isNull();
        // And: all i18n fields propagated end-to-end
        assertThat(daoModel.getNameRU()).isEqualTo("Название");
        assertThat(daoModel.getNamePL()).isEqualTo("Nazwa");
        assertThat(daoModel.getDescriptionRU()).isEqualTo("Описание на русском");
        assertThat(daoModel.getDescriptionPL()).isEqualTo("Opis po polsku");
        assertThat(daoModel.getCode()).isEqualTo("TEST-001");
        assertThat(daoModel.getQuantity()).isEqualTo(42);
    }

    @Test
    @DisplayName("Blank field normalization: blank i18n fields are normalized to null on final DaoModel")
    void blankFieldNormalization_blankFieldsBecomesNull() {
        // Given: a create request with some blank i18n fields
        TestCreateRequest request = new TestCreateRequest();
        request.setNameRU("  ");           // blank — should be normalized to null
        request.setNamePL("Nazwa");         // non-blank — should pass through
        request.setDescriptionRU("");       // empty — should be normalized to null
        request.setDescriptionPL("   \t");  // whitespace — should be normalized to null
        request.setCode("CODE-99");
        request.setQuantity(7);

        // When: flow through both mappers
        TestServiceExtendedModel extModel = controllerMapper.toServiceExtendedModel(request);
        TestDaoModel daoModel = serviceMapper.toCreateDaoModel(extModel);

        // Then: blank fields are normalized to null by processI18nEmptyValues
        assertThat(daoModel.getId()).isNull();
        assertThat(daoModel.getNameRU()).isNull();
        assertThat(daoModel.getNamePL()).isEqualTo("Nazwa");
        assertThat(daoModel.getDescriptionRU()).isNull();
        assertThat(daoModel.getDescriptionPL()).isNull();
        // Non-i18n fields propagate as-is
        assertThat(daoModel.getCode()).isEqualTo("CODE-99");
        assertThat(daoModel.getQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("Full field propagation: code and quantity propagate through the full create flow")
    void fullFieldPropagation_codeAndQuantityPropagate() {
        // Given: a create request with specific non-i18n fields
        TestCreateRequest request = new TestCreateRequest();
        request.setNameRU("Тест");
        request.setNamePL("Test");
        request.setDescriptionRU("Описание");
        request.setDescriptionPL("Opis");
        request.setCode("FULL-PROP-123");
        request.setQuantity(999);

        // When: full flow
        TestServiceExtendedModel extModel = controllerMapper.toServiceExtendedModel(request);
        TestDaoModel daoModel = serviceMapper.toCreateDaoModel(extModel);

        // Then: code and quantity propagated end-to-end
        assertThat(daoModel.getCode()).isEqualTo("FULL-PROP-123");
        assertThat(daoModel.getQuantity()).isEqualTo(999);
        // And: i18n fields also propagated correctly
        assertThat(daoModel.getNameRU()).isEqualTo("Тест");
        assertThat(daoModel.getNamePL()).isEqualTo("Test");
        assertThat(daoModel.getDescriptionRU()).isEqualTo("Описание");
        assertThat(daoModel.getDescriptionPL()).isEqualTo("Opis");
        // And: id remains null
        assertThat(daoModel.getId()).isNull();
    }
}
