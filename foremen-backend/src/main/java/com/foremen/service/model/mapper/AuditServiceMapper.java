package com.foremen.service.model.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.audit.AuditPerformedByResolver;
import com.foremen.service.model.AuditServiceExtendedModel;
import com.foremen.service.model.AuditServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

/**
 * Abstract mapper (rather than a plain interface) so it can resolve the stored
 * {@code performedBy} value — which the write side persists as {@code String.valueOf(userId)}
 * (the JWT {@code sub} claim) — into the acting user's human-readable {@code name} at read time.
 * The schema is unchanged (still stores the id string); the id&rarr;name resolution is delegated to
 * {@link AuditPerformedByResolver} so the exact same semantics are shared with the per-entity
 * {@code /{resource}/audit/{id}} endpoint (single source of truth).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class AuditServiceMapper
        implements ServiceToDaoMapper<AuditLogEntity, AuditServiceModel, AuditServiceExtendedModel> {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected AuditPerformedByResolver performedByResolver;

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    @Mapping(source = "performedBy", target = "performedBy", qualifiedByName = "resolvePerformedByName")
    @Mapping(source = "snapshotBefore", target = "snapshotBefore", qualifiedByName = "jsonStringToMap")
    @Mapping(source = "snapshotAfter", target = "snapshotAfter", qualifiedByName = "jsonStringToMap")
    public abstract AuditServiceModel toServiceModel(AuditLogEntity entity);

    @Override
    @Mapping(source = "performedBy", target = "performedBy", qualifiedByName = "resolvePerformedByName")
    @Mapping(source = "snapshotBefore", target = "snapshotBefore", qualifiedByName = "jsonStringToMap")
    @Mapping(source = "snapshotAfter", target = "snapshotAfter", qualifiedByName = "jsonStringToMap")
    public abstract AuditServiceExtendedModel toServiceExtendedModel(AuditLogEntity entity);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "snapshotBefore", ignore = true)
    @Mapping(target = "snapshotAfter", ignore = true)
    public abstract AuditLogEntity toCreateDaoModel(AuditServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "snapshotBefore", ignore = true)
    @Mapping(target = "snapshotAfter", ignore = true)
    public abstract void updateFields(AuditServiceExtendedModel source, @MappingTarget AuditLogEntity target);

    @Named("jsonStringToMap")
    public Map<String, Object> jsonStringToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    /**
     * Resolves the stored {@code performedBy} id string into the acting user's {@code name},
     * delegating to {@link AuditPerformedByResolver} so the semantics live in one place.
     */
    @Named("resolvePerformedByName")
    protected String resolvePerformedByName(String performedBy) {
        return performedByResolver.resolveName(performedBy);
    }
}
