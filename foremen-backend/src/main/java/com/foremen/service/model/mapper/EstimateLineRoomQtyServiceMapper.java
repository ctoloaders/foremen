package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.EstimateLineRoomQtyServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomQtyServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Set;

/**
 * Service mapper for {@link EstimateLineRoomQtyEntity} (FOR-05-03, Requirement 3), following the
 * FOR-04 {@code RoomServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an
 * injected {@link EntityManager} so the flat {@code lineId}/{@code roomId} become managed references
 * via {@code getReference(...)} without a SELECT.
 *
 * <p>EstimateLineRoomQty has no own i18n {@code name}, so {@link #getI18nSupportedProperties()}
 * returns an empty set. The read model resolves {@code roomLabel} (plain, not localized — rooms use
 * a free-text {@code label}) in an {@code @AfterMapping}.
 *
 * <p>{@code quantity} is an <em>input</em> value here, not derived (it is the per-room quantity that
 * feeds the owning line's derived total, R3.3) — it is mapped straight through on both directions,
 * unlike the derived columns on the other three estimate mappers (task 5.2).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class EstimateLineRoomQtyServiceMapper
        implements ServiceToDaoMapper<EstimateLineRoomQtyEntity, EstimateLineRoomQtyServiceModel,
        EstimateLineRoomQtyServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected EstimateLineEntity lineRef(Long id) {
        return id == null ? null : entityManager.getReference(EstimateLineEntity.class, id);
    }

    protected RoomEntity roomRef(Long id) {
        return id == null ? null : entityManager.getReference(RoomEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "line", expression = "java(lineRef(source.getLineId()))")
    @Mapping(target = "room", expression = "java(roomRef(source.getRoomId()))")
    public abstract EstimateLineRoomQtyEntity toCreateDaoModel(EstimateLineRoomQtyServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "line", expression = "java(lineRef(source.getLineId()))")
    @Mapping(target = "room", expression = "java(roomRef(source.getRoomId()))")
    public abstract void updateFields(
            EstimateLineRoomQtyServiceExtendedModel source, @MappingTarget EstimateLineRoomQtyEntity target);

    @Override
    @Mapping(target = "lineId", source = "line.id")
    @Mapping(target = "roomId", source = "room.id")
    @Mapping(target = "roomLabel", ignore = true)
    public abstract EstimateLineRoomQtyServiceModel toServiceModel(EstimateLineRoomQtyEntity source);

    @Override
    @Mapping(target = "lineId", source = "line.id")
    @Mapping(target = "roomId", source = "room.id")
    public abstract EstimateLineRoomQtyServiceExtendedModel toServiceExtendedModel(EstimateLineRoomQtyEntity source);

    /** Resolves the referenced room's plain (not localized) {@code label} into {@code roomLabel}. */
    @AfterMapping
    protected void resolveReferencedNames(
            @MappingTarget EstimateLineRoomQtyServiceModel target, EstimateLineRoomQtyEntity source) {
        RoomEntity room = source.getRoom();
        if (room != null) {
            target.setRoomLabel(room.getLabel());
        }
    }
}
