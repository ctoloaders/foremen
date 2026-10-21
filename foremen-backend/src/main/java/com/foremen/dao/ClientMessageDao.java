package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ClientMessageEntity;

/**
 * FOR-06-12 (Requirements 1.5, 5, 6; design §ClientMessageService) — DAO for
 * {@link ClientMessageEntity}. The generic {@link AdminDao} CRUD + {@code JpaSpecificationExecutor}
 * surface backs the {@code ClientMessageService}, which is a {@code ProjectScopedService}: its LIST
 * reads flow through the inherited specification ({@code addRequiredQuery()} project filter on
 * {@code "project.id"}) and its by-id reads/mutations run through the inherited {@code findById} /
 * {@code save} / {@code delete} with the per-id project-access guard.
 *
 * <p>Adds the project-scoped thread finder the read model needs: all of a project's messages as one
 * ordered conversation (by {@code createdDate} ascending, R1.5), with the author eagerly fetched so
 * the per-message author name resolves without an N+1. Translations and media are left to their own
 * lazy collections (hydrated per message by the mapper / the media service).
 */
@Repository
public interface ClientMessageDao extends AdminDao<ClientMessageEntity, Long> {

    /**
     * The project's whole thread, ordered by {@code createdDate} ascending (R1.5), with the
     * {@code author} fetch-joined for the read projection.
     *
     * @param projectId the owning project id
     * @return the ordered messages of the project, empty when none
     */
    @Query("SELECT m FROM ClientMessageEntity m "
            + "JOIN FETCH m.author "
            + "WHERE m.project.id = :projectId "
            + "ORDER BY m.createdDate ASC, m.id ASC")
    List<ClientMessageEntity> findThreadByProjectId(@Param("projectId") Long projectId);
}
