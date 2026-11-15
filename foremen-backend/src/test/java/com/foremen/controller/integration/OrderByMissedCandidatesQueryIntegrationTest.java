package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-04 task 3.14: {@code PurchasePositionDao.findOrderByMissedCandidates} against PostgreSQL.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction. Validates Requirements 14.2, 14.3.
 */
class OrderByMissedCandidatesQueryIntegrationTest extends ProcurementIntegrationTestBase {

    @Autowired
    private PurchasePositionDao positionDao;

    private PurchasePositionEntity pos(ProjectEntity project, SourcingPath path, PositionStatus status,
                                       LocalDate orderBy, LocalDate notifiedFor) {
        PurchasePositionEntity p = position(project, path, status);
        p.setOrderByDate(orderBy);
        p.setOrderByNotifiedFor(notifiedFor);
        positionDao.save(p);
        em.flush();
        return p;
    }

    @Test
    @DisplayName("candidates: reached order-by, before ORDERED, not CLIENT_OWN, marker differs from date")
    void candidates() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate today = LocalDate.of(2025, 3, 10);
        LocalDate due = today.minusDays(2);

        PurchasePositionEntity hit = pos(project, SourcingPath.AGREED, PositionStatus.PLANNED, due, null);
        PurchasePositionEntity rearmed = pos(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER, today,
                due);
        PurchasePositionEntity notified = pos(project, SourcingPath.AGREED, PositionStatus.PLANNED, due, due);
        PurchasePositionEntity future = pos(project, SourcingPath.AGREED, PositionStatus.PLANNED,
                today.plusDays(1), null);
        PurchasePositionEntity ordered = pos(project, SourcingPath.AGREED, PositionStatus.ORDERED, due, null);
        PurchasePositionEntity cancelled = pos(project, SourcingPath.AGREED, PositionStatus.CANCELLED, due, null);
        PurchasePositionEntity own = pos(project, SourcingPath.CLIENT_OWN, PositionStatus.PLANNED, due, null);
        PurchasePositionEntity noDate = pos(project, SourcingPath.AGREED, PositionStatus.PLANNED, null, null);
        em.clear();

        assertThat(positionDao.findOrderByMissedCandidates(today))
                .extracting(PurchasePositionEntity::getId)
                .contains(hit.getId(), rearmed.getId())
                .doesNotContain(notified.getId(), future.getId(), ordered.getId(), cancelled.getId(),
                        own.getId(), noDate.getId());
    }
}
