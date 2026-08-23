package com.foremen.service.model.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.AuditServiceExtendedModel;
import com.foremen.service.model.AuditServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import java.util.Map;
import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface AuditServiceMapper
        extends ServiceToDaoMapper<AuditLogEntity, AuditServiceModel, AuditServiceExtendedModel> {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    @Mapping(source = "snapshotBefore", target = "snapshotBefore", qualifiedByName = "jsonStringToMap")
    @Mapping(source = "snapshotAfter", target = "snapshotAfter", qualifiedByName = "jsonStringToMap")
    AuditServiceModel toServiceModel(AuditLogEntity entity);

    @Override
    @Mapping(source = "snapshotBefore", target = "snapshotBefore", qualifiedByName = "jsonStringToMap")
    @Mapping(source = "snapshotAfter", target = "snapshotAfter", qualifiedByName = "jsonStringToMap")
    AuditServiceExtendedModel toServiceExtendedModel(AuditLogEntity entity);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "snapshotBefore", ignore = true)
    @Mapping(target = "snapshotAfter", ignore = true)
    AuditLogEntity toCreateDaoModel(AuditServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "snapshotBefore", ignore = true)
    @Mapping(target = "snapshotAfter", ignore = true)
    void updateFields(AuditServiceExtendedModel source, @MappingTarget AuditLogEntity target);

    @Named("jsonStringToMap")
    default Map<String, Object> jsonStringToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }
}
