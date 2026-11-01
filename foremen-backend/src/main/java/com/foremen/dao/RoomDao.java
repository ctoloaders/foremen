package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.RoomEntity;

@Repository
public interface RoomDao extends AdminDao<RoomEntity, Long> {

    /**
     * Loads every room of a project (FOR-05-05, design §B4): the column set of the works&times;rooms
     * matrix and the target set of the {@code Apply_Package} / {@code Work_Row_Apply} calculate cores.
     * The work's {@code Room_Type_Attachment} then narrows this set to rooms of the attached types
     * (all rooms when empty, R10.2/R10.3). Ordered by id for a deterministic apply order.
     *
     * @param projectId the owning project id
     * @return the project's rooms in their fixed project order ({@code sortOrder}, then id — FOR-05-02
     *         Round 4), empty when the project has none
     */
    @Query("SELECT r FROM RoomEntity r WHERE r.project.id = :projectId ORDER BY r.sortOrder, r.id")
    List<RoomEntity> findByProjectId(@Param("projectId") Long projectId);

    /** FOR-05-02 Round 4: the highest {@code sortOrder} in a project, or {@code null} when it has no rooms. */
    @Query("SELECT MAX(r.sortOrder) FROM RoomEntity r WHERE r.project.id = :projectId")
    Integer findMaxSortOrder(@Param("projectId") Long projectId);

    /** FOR-05-02 Round 4: the highest {@code sortOrder} among the project's rooms other than {@code roomId}. */
    @Query("SELECT MAX(r.sortOrder) FROM RoomEntity r WHERE r.project.id = :projectId AND r.id <> :roomId")
    Integer findMaxSortOrderExcluding(@Param("projectId") Long projectId, @Param("roomId") Long roomId);
}
