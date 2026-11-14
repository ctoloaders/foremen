package com.foremen.dao;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ProjectScheduleBarEntity;

/**
 * DAO for {@link ProjectScheduleBarEntity} (FOR-05-10, Requirement 6.2): the planned bars of a
 * project schedule.
 *
 * <p>Bars are an owned child collection of {@link com.foremen.dao.model.ProjectScheduleEntity}
 * ({@code cascade = ALL}, {@code orphanRemoval = true}), so they are normally persisted and removed
 * through the aggregate root rather than this DAO. This repository exists for direct lookups/counts
 * and to keep the entity's CRUD surface available where the service needs it.
 */
@Repository
public interface ProjectScheduleBarDao extends AdminDao<ProjectScheduleBarEntity, Long> {

    /**
     * Earliest bar start (min {@code startHalfDays}) per (project, work category) with the project's
     * start date (FOR-11-01 R5.5). The cross product of the two id sets is over-fetched; the caller
     * keeps only the requested pairs.
     */
    @Query("select new com.foremen.dao.ProjectScheduleBarDao$EarliestBarRow("
            + "p.id, b.workCategory.id, p.startDate, min(b.startHalfDays)) "
            + "from ProjectScheduleBarEntity b join b.schedule s join s.project p "
            + "where p.id in :projectIds and b.workCategory.id in :categoryIds "
            + "group by p.id, b.workCategory.id, p.startDate")
    List<EarliestBarRow> findEarliestBarStarts(@Param("projectIds") Collection<Long> projectIds,
                                               @Param("categoryIds") Collection<Long> categoryIds);

    /** Projection row of {@link #findEarliestBarStarts}. */
    record EarliestBarRow(Long projectId, Long categoryId, LocalDate projectStartDate,
                          Integer minStartHalfDays) {
    }
}
