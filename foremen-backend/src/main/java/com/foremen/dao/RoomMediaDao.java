package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.RoomMediaEntity;

/**
 * DAO for {@link RoomMediaEntity} (FOR-05-02, Requirement 10.1): the generic {@link AdminDao} CRUD
 * surface plus a room-scoped finder.
 */
@Repository
public interface RoomMediaDao extends AdminDao<RoomMediaEntity, Long> {

    /** Every media row of a room. */
    List<RoomMediaEntity> findByRoomId(Long roomId);

    /** Number of media rows of a room (count-limit check). */
    long countByRoomId(Long roomId);

    /** Grouped counts {@code [roomId, count]} for a set of rooms: one query, no N+1 (Req 10.4). */
    @Query("SELECT m.room.id, COUNT(m) FROM RoomMediaEntity m WHERE m.room.id IN :roomIds GROUP BY m.room.id")
    List<Object[]> countGroupedByRoomIds(@Param("roomIds") Collection<Long> roomIds);

    /** Storage uris of every media row of the given rooms (object cleanup on room delete, Req 10.5). */
    @Query("SELECT m.storageUri FROM RoomMediaEntity m WHERE m.room.id IN :roomIds")
    List<String> findStorageUrisByRoomIds(@Param("roomIds") Collection<Long> roomIds);
}
