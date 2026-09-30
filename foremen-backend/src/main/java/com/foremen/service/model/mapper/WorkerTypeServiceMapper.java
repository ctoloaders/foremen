package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkerTypeServiceExtendedModel;
import com.foremen.service.model.WorkerTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

/**
 * FOR-05-06 — WorkerType service&harr;DAO mapper. Follows the {@code RoomTypeServiceMapper} /
 * {@code MeasurementUnitServiceMapper} pattern: {@code name} is the single i18n property, {@code id}
 * is never mapped on create, and {@code code} is immutable on update.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkerTypeServiceMapper
        extends ServiceToDaoMapper<WorkerTypeEntity, WorkerTypeServiceModel, WorkerTypeServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    WorkerTypeEntity toCreateDaoModel(WorkerTypeServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(WorkerTypeServiceExtendedModel source, @MappingTarget WorkerTypeEntity target);
}
