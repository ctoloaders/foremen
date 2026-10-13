package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.UnplannedExpenseCategoryServiceExtendedModel;
import com.foremen.service.model.UnplannedExpenseCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface UnplannedExpenseCategoryServiceMapper
        extends ServiceToDaoMapper<UnplannedExpenseCategoryEntity, UnplannedExpenseCategoryServiceModel, UnplannedExpenseCategoryServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    UnplannedExpenseCategoryEntity toCreateDaoModel(UnplannedExpenseCategoryServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(UnplannedExpenseCategoryServiceExtendedModel source, @MappingTarget UnplannedExpenseCategoryEntity target);
}
