package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkCategoryServiceExtendedModel;
import com.foremen.service.model.WorkCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface WorkCategoryServiceMapper
        extends ServiceToDaoMapper<WorkCategoryEntity, WorkCategoryServiceModel, WorkCategoryServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    WorkCategoryEntity toCreateDaoModel(WorkCategoryServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(WorkCategoryServiceExtendedModel source, @MappingTarget WorkCategoryEntity target);
}
