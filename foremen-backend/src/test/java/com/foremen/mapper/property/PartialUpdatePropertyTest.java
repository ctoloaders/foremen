package com.foremen.mapper.property;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mapstruct.factory.Mappers;

import java.util.Objects;

/**
 * Property 4: Partial Update Semantics (updateFields)
 *
 * For any ServiceExtendedModel with a mix of null and non-null fields,
 * and for any fully-populated DaoModel target:
 * - Non-null source fields overwrite target fields
 * - Null source fields leave target unchanged
 * - id is never modified
 *
 * Validates: Requirements 2.6, 6.1, 6.2, 6.5, 8.3
 */
class PartialUpdatePropertyTest {

    private final TestEntityServiceMapper mapper = Mappers.getMapper(TestEntityServiceMapper.class);

    @Property(tries = 100)
    void partialUpdatePreservesNullFieldsAndOverwritesNonNull(
            @ForAll("partialServiceExtendedModels") TestServiceExtendedModel source,
            @ForAll("fullyPopulatedDaoModels") TestDaoModel target
    ) {
        // Save original target values before updateFields
        Long originalId = target.getId();
        String originalNameRU = target.getNameRU();
        String originalNamePL = target.getNamePL();
        String originalDescriptionRU = target.getDescriptionRU();
        String originalDescriptionPL = target.getDescriptionPL();
        String originalCode = target.getCode();
        Integer originalQuantity = target.getQuantity();

        // Execute updateFields
        mapper.updateFields(source, target);

        // id is NEVER modified
        assert Objects.equals(originalId, target.getId())
                : "id should never be modified by updateFields";

        // For each field: if source non-null → target overwritten; if source null → target unchanged
        // Note: processI18nEmptyValues runs @AfterMapping and normalizes blank i18n strings to null.
        // Since we generate non-blank strings only, non-null source i18n fields won't be normalized.

        // nameRU
        if (source.getNameRU() != null) {
            assert Objects.equals(source.getNameRU(), target.getNameRU())
                    : "Non-null source nameRU should overwrite target";
        } else {
            assert Objects.equals(originalNameRU, target.getNameRU())
                    : "Null source nameRU should leave target unchanged";
        }

        // namePL
        if (source.getNamePL() != null) {
            assert Objects.equals(source.getNamePL(), target.getNamePL())
                    : "Non-null source namePL should overwrite target";
        } else {
            assert Objects.equals(originalNamePL, target.getNamePL())
                    : "Null source namePL should leave target unchanged";
        }

        // descriptionRU
        if (source.getDescriptionRU() != null) {
            assert Objects.equals(source.getDescriptionRU(), target.getDescriptionRU())
                    : "Non-null source descriptionRU should overwrite target";
        } else {
            assert Objects.equals(originalDescriptionRU, target.getDescriptionRU())
                    : "Null source descriptionRU should leave target unchanged";
        }

        // descriptionPL
        if (source.getDescriptionPL() != null) {
            assert Objects.equals(source.getDescriptionPL(), target.getDescriptionPL())
                    : "Non-null source descriptionPL should overwrite target";
        } else {
            assert Objects.equals(originalDescriptionPL, target.getDescriptionPL())
                    : "Null source descriptionPL should leave target unchanged";
        }

        // code (non-i18n field, not affected by processI18nEmptyValues)
        if (source.getCode() != null) {
            assert Objects.equals(source.getCode(), target.getCode())
                    : "Non-null source code should overwrite target";
        } else {
            assert Objects.equals(originalCode, target.getCode())
                    : "Null source code should leave target unchanged";
        }

        // quantity (non-i18n field, not affected by processI18nEmptyValues)
        if (source.getQuantity() != null) {
            assert Objects.equals(source.getQuantity(), target.getQuantity())
                    : "Non-null source quantity should overwrite target";
        } else {
            assert Objects.equals(originalQuantity, target.getQuantity())
                    : "Null source quantity should leave target unchanged";
        }
    }

    @Provide
    Arbitrary<TestServiceExtendedModel> partialServiceExtendedModels() {
        Arbitrary<String> nonBlankStrings = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
        Arbitrary<String> nullableStrings = nonBlankStrings.injectNull(0.5);
        Arbitrary<Integer> nullableIntegers = Arbitraries.integers()
                .between(1, 10000)
                .injectNull(0.5);
        Arbitrary<Long> nullableIds = Arbitraries.longs()
                .between(1L, 100000L)
                .injectNull(0.5);

        return Combinators.combine(
                nullableIds,
                nullableStrings, nullableStrings,
                nullableStrings, nullableStrings,
                nullableStrings, nullableIntegers
        ).as((id, nameRU, namePL, descRU, descPL, code, quantity) -> {
            TestServiceExtendedModel model = new TestServiceExtendedModel();
            model.setId(id);
            model.setNameRU(nameRU);
            model.setNamePL(namePL);
            model.setDescriptionRU(descRU);
            model.setDescriptionPL(descPL);
            model.setCode(code);
            model.setQuantity(quantity);
            return model;
        });
    }

    @Provide
    Arbitrary<TestDaoModel> fullyPopulatedDaoModels() {
        Arbitrary<String> nonBlankStrings = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
        Arbitrary<Integer> positiveIntegers = Arbitraries.integers()
                .between(1, 10000);
        Arbitrary<Long> positiveIds = Arbitraries.longs()
                .between(1L, 100000L);

        return Combinators.combine(
                positiveIds,
                nonBlankStrings, nonBlankStrings,
                nonBlankStrings, nonBlankStrings,
                nonBlankStrings, positiveIntegers
        ).as((id, nameRU, namePL, descRU, descPL, code, quantity) -> {
            TestDaoModel model = new TestDaoModel();
            model.setId(id);
            model.setNameRU(nameRU);
            model.setNamePL(namePL);
            model.setDescriptionRU(descRU);
            model.setDescriptionPL(descPL);
            model.setCode(code);
            model.setQuantity(quantity);
            return model;
        });
    }
}
