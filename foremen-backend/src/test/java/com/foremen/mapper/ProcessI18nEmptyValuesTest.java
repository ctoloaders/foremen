package com.foremen.mapper;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.mapper.fixture.TestServiceModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessI18nEmptyValuesTest {

    private TestEntityServiceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(TestEntityServiceMapper.class);
    }

    @Test
    @DisplayName("Empty properties set: no field modifications")
    void emptyPropertiesSet_noFieldModifications() {
        ServiceToDaoMapper<TestDaoModel, TestServiceModel, TestServiceExtendedModel> emptyPropsMapper = new ServiceToDaoMapper<>() {
            @Override
            public Set<String> getI18nSupportedProperties() {
                return Set.of();
            }

            @Override
            public TestServiceModel toServiceModel(TestDaoModel source) {
                return null;
            }

            @Override
            public TestServiceExtendedModel toServiceExtendedModel(TestDaoModel source) {
                return null;
            }

            @Override
            public TestDaoModel toCreateDaoModel(TestServiceExtendedModel source) {
                return null;
            }

            @Override
            public void updateFields(TestServiceExtendedModel source, TestDaoModel target) {
            }
        };

        TestDaoModel target = new TestDaoModel();
        target.setId(1L);
        target.setNameRU("Название");
        target.setNamePL("Nazwa");
        target.setDescriptionRU("Описание");
        target.setDescriptionPL("Opis");
        target.setCode("CODE-1");
        target.setQuantity(42);

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        emptyPropsMapper.processI18nEmptyValues(target, source);

        assertThat(target.getId()).isEqualTo(1L);
        assertThat(target.getNameRU()).isEqualTo("Название");
        assertThat(target.getNamePL()).isEqualTo("Nazwa");
        assertThat(target.getDescriptionRU()).isEqualTo("Описание");
        assertThat(target.getDescriptionPL()).isEqualTo("Opis");
        assertThat(target.getCode()).isEqualTo("CODE-1");
        assertThat(target.getQuantity()).isEqualTo(42);
    }

    @Test
    @DisplayName("Blank strings normalized to null")
    void blankStrings_normalizedToNull() {
        TestDaoModel target = new TestDaoModel();
        target.setNameRU("");
        target.setNamePL("   ");
        target.setDescriptionRU("\t");
        target.setDescriptionPL("  \n  ");

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        mapper.processI18nEmptyValues(target, source);

        assertThat(target.getNameRU()).isNull();
        assertThat(target.getNamePL()).isNull();
        assertThat(target.getDescriptionRU()).isNull();
        assertThat(target.getDescriptionPL()).isNull();
    }

    @Test
    @DisplayName("Non-blank strings unchanged")
    void nonBlankStrings_unchanged() {
        TestDaoModel target = new TestDaoModel();
        target.setNameRU("Тест");
        target.setNamePL("Test");
        target.setDescriptionRU("Описание");
        target.setDescriptionPL("Opis");

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        mapper.processI18nEmptyValues(target, source);

        assertThat(target.getNameRU()).isEqualTo("Тест");
        assertThat(target.getNamePL()).isEqualTo("Test");
        assertThat(target.getDescriptionRU()).isEqualTo("Описание");
        assertThat(target.getDescriptionPL()).isEqualTo("Opis");
    }

    @Test
    @DisplayName("Null fields remain null")
    void nullFields_remainNull() {
        TestDaoModel target = new TestDaoModel();
        target.setNameRU(null);
        target.setNamePL(null);
        target.setDescriptionRU(null);
        target.setDescriptionPL(null);

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        mapper.processI18nEmptyValues(target, source);

        assertThat(target.getNameRU()).isNull();
        assertThat(target.getNamePL()).isNull();
        assertThat(target.getDescriptionRU()).isNull();
        assertThat(target.getDescriptionPL()).isNull();
    }

    @Test
    @DisplayName("Mixed case: blank normalized, non-blank unchanged, null stays null")
    void mixedCase_eachBehavesAccordingToValue() {
        TestDaoModel target = new TestDaoModel();
        target.setNameRU("");          // blank → null
        target.setNamePL("Nazwa");     // non-blank → unchanged
        target.setDescriptionRU(null); // null → stays null
        target.setDescriptionPL("  "); // blank → null

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        mapper.processI18nEmptyValues(target, source);

        assertThat(target.getNameRU()).isNull();
        assertThat(target.getNamePL()).isEqualTo("Nazwa");
        assertThat(target.getDescriptionRU()).isNull();
        assertThat(target.getDescriptionPL()).isNull();
    }

    @Test
    @DisplayName("Non-i18n fields untouched regardless of their values")
    void nonI18nFields_untouched() {
        TestDaoModel target = new TestDaoModel();
        target.setId(99L);
        target.setCode("");
        target.setQuantity(0);
        target.setNameRU("  ");  // blank i18n field → normalized

        TestServiceExtendedModel source = new TestServiceExtendedModel();

        mapper.processI18nEmptyValues(target, source);

        // non-i18n fields remain exactly as set, even if blank
        assertThat(target.getId()).isEqualTo(99L);
        assertThat(target.getCode()).isEqualTo("");
        assertThat(target.getQuantity()).isEqualTo(0);
        // i18n field was normalized
        assertThat(target.getNameRU()).isNull();
    }
}
