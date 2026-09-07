package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Service mapper for {@link RoomEntity}, following the FOR-04 {@code WorkPriceServiceMapper} FK
 * pattern: an <b>abstract class</b> holding an injected {@link EntityManager} so it can turn the
 * flat {@code projectId} / {@code roomTypeId} into managed references via {@code getReference(...)}
 * without triggering a SELECT.
 *
 * <p>Room has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an empty
 * set. The list/read model resolves the referenced project name ({@code project.name}, not
 * localized) and the localized room-type name ({@code roomTypeName}) in an {@code @AfterMapping}
 * using the framework request-locale rule (RU when the request locale language is {@code ru}, PL
 * otherwise — PL fallback).
 *
 * <p>The flat metric/source pairs, counts and gaps are copied field-by-field; the geometry POJO is
 * carried through by reference. The service normalization step owns writing calculated values and
 * stamping the source flags before {@link #toCreateDaoModel}/{@link #updateFields} run.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class RoomServiceMapper
        implements ServiceToDaoMapper<RoomEntity, RoomServiceModel, RoomServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected RoomTypeEntity roomTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(RoomTypeEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "roomType", expression = "java(roomTypeRef(source.getRoomTypeId()))")
    public abstract RoomEntity toCreateDaoModel(RoomServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "roomType", expression = "java(roomTypeRef(source.getRoomTypeId()))")
    public abstract void updateFields(RoomServiceExtendedModel source, @MappingTarget RoomEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "roomTypeId", source = "roomType.id")
    public abstract RoomServiceModel toServiceModel(RoomEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "roomTypeId", source = "roomType.id")
    public abstract RoomServiceExtendedModel toServiceExtendedModel(RoomEntity source);

    /**
     * Resolves the referenced project name ({@code project.name}) and the localized room-type name
     * into {@code projectName}/{@code roomTypeName}, using the per-request locale rule (RU when the
     * request locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget RoomServiceModel target, RoomEntity source) {
        ProjectEntity project = source.getProject();
        if (project != null) {
            target.setProjectName(project.getName());
        }
        RoomTypeEntity roomType = source.getRoomType();
        if (roomType != null) {
            target.setRoomTypeName(isRussianLocale() ? roomType.getNameRU() : roomType.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
