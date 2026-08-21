package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface UserServiceMapper
        extends ServiceToDaoMapper<UserEntity, UserServiceModel, UserServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(source.getRole() != null ? source.getRole().getNameRU() : null)")
    UserServiceModel toServiceModel(UserEntity source);

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(source.getRole() != null ? source.getRole().getNameRU() : null)")
    UserServiceExtendedModel toServiceExtendedModel(UserEntity source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    UserEntity toCreateDaoModel(UserServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    void updateFields(UserServiceExtendedModel source, @MappingTarget UserEntity target);
}
