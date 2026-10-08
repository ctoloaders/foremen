package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.SignableDocumentEntity;

/**
 * DAO for {@link SignableDocumentEntity} (FOR-05-08, Requirements 1.1, 1.8), following the
 * {@code OfferDao} finder convention: the generic {@link AdminDao} CRUD surface plus a
 * project-scoped finder backing the project-workspace signing tab listing.
 *
 * <p>The document is project-scoped ({@code getProjectIdPath() = "project.id"}); the
 * {@code JpaSpecificationExecutor} surface inherited from {@link AdminDao} is what
 * {@code ProjectScopedService} uses to auto-filter LIST reads by the caller's allowed projects.
 */
@Repository
public interface SignableDocumentDao extends AdminDao<SignableDocumentEntity, Long> {

    /**
     * Every document of a project, newest first — a convenience finder for the signing tab listing.
     *
     * @param projectId the owning project id
     * @return the project's documents, newest first
     */
    List<SignableDocumentEntity> findByProjectIdOrderByIdDesc(Long projectId);
}
