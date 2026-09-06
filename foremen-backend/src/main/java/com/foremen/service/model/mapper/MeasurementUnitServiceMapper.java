package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MeasurementUnitServiceExtendedModel;
import com.foremen.service.model.MeasurementUnitServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MeasurementUnitServiceMapper
        extends ServiceToDaoMapper<MeasurementUnitEntity, MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MeasurementUnitEntity toCreateDaoModel(MeasurementUnitServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MeasurementUnitServiceExtendedModel source, @MappingTarget MeasurementUnitEntity target);
}
