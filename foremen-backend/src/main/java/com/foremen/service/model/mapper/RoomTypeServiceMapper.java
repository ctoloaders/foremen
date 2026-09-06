package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.RoomTypeServiceExtendedModel;
import com.foremen.service.model.RoomTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface RoomTypeServiceMapper
        extends ServiceToDaoMapper<RoomTypeEntity, RoomTypeServiceModel, RoomTypeServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    RoomTypeEntity toCreateDaoModel(RoomTypeServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(RoomTypeServiceExtendedModel source, @MappingTarget RoomTypeEntity target);
}
