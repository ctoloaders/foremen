package com.foremen.mapper.property;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 3: DAO → ServiceExtendedModel → DAO Round-Trip Preserves Data
 *
 * For any DaoModel instance, calling toServiceExtendedModel(dao) followed by
 * toCreateDaoModel(serviceExtendedModel) SHALL produce a result DaoModel where
 * all fields (excluding id) satisfy Objects.equals(original.getField(), result.getField()).
 *
 * Validates: Requirements 8.2
 */
class RoundTripPropertyTest {

    private final TestEntityServiceMapper mapper = Mappers.getMapper(TestEntityServiceMapper.class);

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Property(tries = 100)
    void daoToExtendedModelToDaoRoundTripPreservesAllNonIdFields(
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL,
            @ForAll("nonBlankStrings") String descriptionRU,
            @ForAll("nonBlankStrings") String descriptionPL,
            @ForAll("nonBlankStrings") String code,
            @ForAll Integer quantity
    ) {
        // Arrange: create a DAO model with non-blank fields
        TestDaoModel original = new TestDaoModel();
        original.setId(42L);
        original.setNameRU(nameRU);
        original.setNamePL(namePL);
        original.setDescriptionRU(descriptionRU);
        original.setDescriptionPL(descriptionPL);
        original.setCode(code);
        original.setQuantity(quantity);

        // Act: round-trip DAO → ServiceExtendedModel → DAO
        TestServiceExtendedModel extended = mapper.toServiceExtendedModel(original);
        TestDaoModel result = mapper.toCreateDaoModel(extended);

        // Assert: all non-id fields are preserved
        assertThat(result.getNameRU()).isEqualTo(original.getNameRU());
        assertThat(result.getNamePL()).isEqualTo(original.getNamePL());
        assertThat(result.getDescriptionRU()).isEqualTo(original.getDescriptionRU());
        assertThat(result.getDescriptionPL()).isEqualTo(original.getDescriptionPL());
        assertThat(result.getCode()).isEqualTo(original.getCode());
        assertThat(result.getQuantity()).isEqualTo(original.getQuantity());

        // id should be null because toCreateDaoModel ignores id
        assertThat(result.getId()).isNull();
    }
}
