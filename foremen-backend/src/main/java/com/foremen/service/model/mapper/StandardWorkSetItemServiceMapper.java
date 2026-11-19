package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.StandardWorkSetItemEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceModel;

import jakarta.persistence.EntityManager;

@Mapper(config = ForemenMapperConfig.class)
public abstract class StandardWorkSetItemServiceMapper implements ServiceToDaoMapper<
        StandardWorkSetItemEntity, StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected RoomTypeEntity roomTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(RoomTypeEntity.class, id);
    }

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomType", expression = "java(roomTypeRef(source.roomTypeId()))")
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    public abstract StandardWorkSetItemEntity toCreateDaoModel(StandardWorkSetItemServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomType", expression = "java(roomTypeRef(source.roomTypeId()))")
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    public abstract void updateFields(StandardWorkSetItemServiceExtendedModel source,
                                      @MappingTarget StandardWorkSetItemEntity target);

    @Override
    @Mapping(target = "roomTypeId", source = "roomType.id")
    @Mapping(target = "roomTypeName", ignore = true)
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workItemName", ignore = true)
    @Mapping(target = "workItemCode", source = "workItem.code")
    @Mapping(target = "workCategoryName", ignore = true)
    public abstract StandardWorkSetItemServiceModel toServiceModel(StandardWorkSetItemEntity source);

    @Override
    @Mapping(target = "roomTypeId", source = "roomType.id")
    @Mapping(target = "workItemId", source = "workItem.id")
    public abstract StandardWorkSetItemServiceExtendedModel toServiceExtendedModel(StandardWorkSetItemEntity source);

    @AfterMapping
    protected void resolveNames(@MappingTarget StandardWorkSetItemServiceModel target,
                                StandardWorkSetItemEntity source) {
        Locale locale = LocaleContextHolder.getLocale();
        boolean ru = locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
        RoomTypeEntity roomType = source.getRoomType();
        if (roomType != null) {
            target.setRoomTypeName(ru ? roomType.getNameRU() : roomType.getNamePL());
        }
        WorkItemEntity work = source.getWorkItem();
        if (work != null) {
            target.setWorkItemName(ru ? work.getNameRU() : work.getNamePL());
            if (work.getWorkCategory() != null) {
                target.setWorkCategoryName(
                        ru ? work.getWorkCategory().getNameRU() : work.getWorkCategory().getNamePL());
            }
        }
    }
}
