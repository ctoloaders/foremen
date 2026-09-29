package com.foremen.service.model.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.AssortmentGroupServiceExtendedModel;
import com.foremen.service.model.AssortmentGroupServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link AssortmentGroupEntity} (FOR-05-04, Requirement 6.1).
 *
 * <p>An <b>abstract class</b> (was an interface) so it can hold an injected {@link EntityManager}:
 * the M:N {@code roomTypes} association (FOR-05-05 Amendment A1, point B) cannot be auto-mapped
 * from the flat {@code roomTypeIds}, so both write conversions REPLACE the target's room-type set
 * from {@code source.roomTypeIds()} in {@link #applyRoomTypes} (running inside the create/update
 * transaction), and the read conversion maps the set back to sorted ids in
 * {@link #mapRoomTypeIds}. {@code name} is populated by the shared i18n framework from
 * {@code nameRU}/{@code namePL} (PL fallback) via {@link #getI18nSupportedProperties()}.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class AssortmentGroupServiceMapper
        implements ServiceToDaoMapper<AssortmentGroupEntity, AssortmentGroupServiceModel,
        AssortmentGroupServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    public abstract AssortmentGroupEntity toCreateDaoModel(AssortmentGroupServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    public abstract void updateFields(AssortmentGroupServiceExtendedModel source,
                                      @MappingTarget AssortmentGroupEntity target);

    @Override
    @Mapping(target = "roomTypeIds", expression = "java(mapRoomTypeIds(source.getRoomTypes()))")
    public abstract AssortmentGroupServiceModel toServiceModel(AssortmentGroupEntity source);

    @Override
    @Mapping(target = "roomTypeIds", expression = "java(mapRoomTypeIds(source.getRoomTypes()))")
    public abstract AssortmentGroupServiceExtendedModel toServiceExtendedModel(AssortmentGroupEntity source);

    /**
     * REPLACES the group's {@code roomTypes} association from {@code source.roomTypeIds()}: each id
     * is resolved with {@link EntityManager#find} (400 on an explicitly-supplied unknown id). Null
     * or empty {@code roomTypeIds} clears the set. Runs on both create and update.
     */
    @AfterMapping
    protected void applyRoomTypes(AssortmentGroupServiceExtendedModel source,
                                  @MappingTarget AssortmentGroupEntity target) {
        target.getRoomTypes().clear();
        List<Long> ids = source.roomTypeIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            RoomTypeEntity roomType = entityManager.find(RoomTypeEntity.class, id);
            if (roomType == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Room type not found: " + id);
            }
            target.getRoomTypes().add(roomType);
        }
    }

    /** Maps the group's room-type association to a sorted list of ids (empty when none). */
    protected List<Long> mapRoomTypeIds(Set<RoomTypeEntity> roomTypes) {
        List<Long> ids = new ArrayList<>();
        if (roomTypes != null) {
            for (RoomTypeEntity roomType : roomTypes) {
                if (roomType != null && roomType.getId() != null) {
                    ids.add(roomType.getId());
                }
            }
        }
        ids.sort(Long::compareTo);
        return ids;
    }
}
