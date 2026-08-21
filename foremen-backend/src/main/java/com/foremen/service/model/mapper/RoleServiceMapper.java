package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.RoleEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.RoleServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface RoleServiceMapper
        extends ServiceToDaoMapper<RoleEntity, RoleServiceModel, RoleServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name", "description");
    }

    @Override
    @Mapping(target = "roleResources", ignore = true)
    @Mapping(target = "id", ignore = true)
    RoleEntity toCreateDaoModel(RoleServiceExtendedModel source);

    @Override
    @Mapping(target = "roleResources", ignore = true)
    @Mapping(target = "id", ignore = true)
    void updateFields(RoleServiceExtendedModel source, @MappingTarget RoleEntity target);
}
