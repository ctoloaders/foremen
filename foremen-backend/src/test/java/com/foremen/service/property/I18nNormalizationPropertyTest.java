package com.foremen.service.property;

import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.service.model.ResourceServiceExtendedModel;
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.mapper.ResourceServiceMapper;
import com.foremen.service.model.mapper.RoleServiceMapper;
import net.jqwik.api.*;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("Feature: FOR-02-03-abac-entities, Property 5: I18n Empty Value Normalization")
class I18nNormalizationPropertyTest {

    private final ResourceServiceMapper resourceMapper = Mappers.getMapper(ResourceServiceMapper.class);
    private final RoleServiceMapper roleMapper = Mappers.getMapper(RoleServiceMapper.class);

    @Property(tries = 100)
    void blankResourceDescriptionNormalizedToNull(
            @ForAll("blankStrings") String blankDescRU,
            @ForAll("blankStrings") String blankDescPL) {

        ResourceServiceExtendedModel model = new ResourceServiceExtendedModel(
                null, "TEST", "ValidNameRU", "ValidNamePL", blankDescRU, blankDescPL);

        ResourceEntity entity = resourceMapper.toCreateDaoModel(model);

        assertNull(entity.getDescriptionRU());
        assertNull(entity.getDescriptionPL());
    }

    @Property(tries = 100)
    void blankRoleFieldsNormalizedToNull(
            @ForAll("blankStrings") String blankNameRU,
            @ForAll("blankStrings") String blankNamePL,
            @ForAll("blankStrings") String blankDescRU,
            @ForAll("blankStrings") String blankDescPL) {

        RoleServiceExtendedModel model = new RoleServiceExtendedModel(
                null, "TEST", blankNameRU, blankNamePL, blankDescRU, blankDescPL, false);

        RoleEntity entity = roleMapper.toCreateDaoModel(model);

        assertNull(entity.getNameRU());
        assertNull(entity.getNamePL());
        assertNull(entity.getDescriptionRU());
        assertNull(entity.getDescriptionPL());
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of("", " ", "  ", "\t", "\n", "   \t\n  ");
    }
}
