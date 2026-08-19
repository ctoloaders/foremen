package com.foremen.mapper.property;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mapstruct.factory.Mappers;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: Field Value Preservation During Create Mapping.
 * Validates: Requirements 8.1
 *
 * For any ServiceExtendedModel with non-null i18n fields, calling toCreateDaoModel
 * SHALL produce a DaoModel where every directly-mapped field (excluding id)
 * satisfies Objects.equals(source.getField(), result.getField()).
 */
class FieldPreservationPropertyTest {

    private final TestEntityServiceMapper mapper = Mappers.getMapper(TestEntityServiceMapper.class);

    @Property(tries = 100)
    @Tag("Feature: FOR-01-04-mapstruct, Property 2: Field Value Preservation During Create Mapping")
    void allDirectlyMappedFieldsArePreservedDuringCreateMapping(
            @ForAll @StringLength(min = 1, max = 50) String nameRU,
            @ForAll @StringLength(min = 1, max = 50) String namePL,
            @ForAll @StringLength(min = 1, max = 50) String descriptionRU,
            @ForAll @StringLength(min = 1, max = 50) String descriptionPL,
            @ForAll @StringLength(min = 1, max = 50) String code,
            @ForAll @IntRange(min = 0, max = 10000) int quantity) {

        // Filter out blank strings — processI18nEmptyValues normalizes blanks to null
        Assume.that(!nameRU.isBlank());
        Assume.that(!namePL.isBlank());
        Assume.that(!descriptionRU.isBlank());
        Assume.that(!descriptionPL.isBlank());
        Assume.that(!code.isBlank());

        // Arrange
        TestServiceExtendedModel source = new TestServiceExtendedModel();
        source.setNameRU(nameRU);
        source.setNamePL(namePL);
        source.setDescriptionRU(descriptionRU);
        source.setDescriptionPL(descriptionPL);
        source.setCode(code);
        source.setQuantity(quantity);

        // Act
        TestDaoModel result = mapper.toCreateDaoModel(source);

        // Assert — all directly-mapped fields are preserved
        assertThat(Objects.equals(source.getNameRU(), result.getNameRU()))
                .as("nameRU should be preserved")
                .isTrue();
        assertThat(Objects.equals(source.getNamePL(), result.getNamePL()))
                .as("namePL should be preserved")
                .isTrue();
        assertThat(Objects.equals(source.getDescriptionRU(), result.getDescriptionRU()))
                .as("descriptionRU should be preserved")
                .isTrue();
        assertThat(Objects.equals(source.getDescriptionPL(), result.getDescriptionPL()))
                .as("descriptionPL should be preserved")
                .isTrue();
        assertThat(Objects.equals(source.getCode(), result.getCode()))
                .as("code should be preserved")
                .isTrue();
        assertThat(Objects.equals(source.getQuantity(), result.getQuantity()))
                .as("quantity should be preserved")
                .isTrue();
    }
}
