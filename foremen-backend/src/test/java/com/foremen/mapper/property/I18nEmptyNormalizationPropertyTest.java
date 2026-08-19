package com.foremen.mapper.property;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import net.jqwik.api.*;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: I18n Empty Value Normalization (processI18nEmptyValues).
 * Validates: Requirements 2.8, 2.9
 *
 * For any DaoModel where locale-specific fields contain blank strings (empty or whitespace-only),
 * calling processI18nEmptyValues SHALL set those blank fields to null. Fields that contain non-blank
 * strings or are already null SHALL remain unchanged. Non-i18n fields (id, code, quantity) SHALL
 * never be modified.
 */
class I18nEmptyNormalizationPropertyTest {

    private final TestEntityServiceMapper mapper = Mappers.getMapper(TestEntityServiceMapper.class);

    @Provide
    Arbitrary<String> i18nFieldValues() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                // Blank strings (empty or whitespace-only)
                Arbitraries.of("", " ", "   ", "\t", "  \t  "),
                // Non-blank strings
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)
        );
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-01-04-mapstruct, Property 6: I18n Empty Value Normalization")
    void blankI18nFieldsAreNormalizedToNull(
            @ForAll("i18nFieldValues") String nameRU,
            @ForAll("i18nFieldValues") String namePL,
            @ForAll("i18nFieldValues") String descriptionRU,
            @ForAll("i18nFieldValues") String descriptionPL,
            @ForAll Long id,
            @ForAll String code,
            @ForAll int quantity) {

        // Arrange — target DaoModel with mixed i18n field values
        TestDaoModel target = new TestDaoModel();
        target.setId(id);
        target.setNameRU(nameRU);
        target.setNamePL(namePL);
        target.setDescriptionRU(descriptionRU);
        target.setDescriptionPL(descriptionPL);
        target.setCode(code);
        target.setQuantity(quantity);

        // Source is required for the @AfterMapping signature but not used by the logic
        TestServiceExtendedModel source = new TestServiceExtendedModel();

        // Act
        mapper.processI18nEmptyValues(target, source);

        // Assert — blank fields normalized to null
        assertI18nField("nameRU", nameRU, target.getNameRU());
        assertI18nField("namePL", namePL, target.getNamePL());
        assertI18nField("descriptionRU", descriptionRU, target.getDescriptionRU());
        assertI18nField("descriptionPL", descriptionPL, target.getDescriptionPL());

        // Assert — non-i18n fields are never modified
        assertThat(target.getId())
                .as("id should never be modified")
                .isEqualTo(id);
        assertThat(target.getCode())
                .as("code should never be modified")
                .isEqualTo(code);
        assertThat(target.getQuantity())
                .as("quantity should never be modified")
                .isEqualTo(quantity);
    }

    private void assertI18nField(String fieldName, String originalValue, String resultValue) {
        if (originalValue == null) {
            assertThat(resultValue)
                    .as("%s: null fields should remain null", fieldName)
                    .isNull();
        } else if (originalValue.isBlank()) {
            assertThat(resultValue)
                    .as("%s: blank fields should be normalized to null", fieldName)
                    .isNull();
        } else {
            assertThat(resultValue)
                    .as("%s: non-blank fields should remain unchanged", fieldName)
                    .isEqualTo(originalValue);
        }
    }
}
