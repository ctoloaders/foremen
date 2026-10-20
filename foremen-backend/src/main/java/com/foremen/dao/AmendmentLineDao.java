package com.foremen.dao;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;

/**
 * FOR-06-08 (Requirement 8; design §"{@code resolveClientUnitPrice} seam change") — DAO for
 * {@link AmendmentLineEntity}. The entity is a cascade child of {@link
 * com.foremen.dao.model.EstimateAmendmentEntity} and is normally reached through the owning
 * amendment's {@code lines} collection; the generic {@link AdminDao} CRUD surface is inherited for
 * consistency, but the one directed query below is the reason this DAO exists.
 *
 * <p>{@link #sumExtraUnitPriceForEffectiveWorkItem(Long, LocalDate)} backs the dormant
 * Effective_Date_Seam in {@code WorkReportService.resolveClientUnitPrice}: it sums
 * {@code extra_unit_price} over every amendment line that targets the given {@code workItem} and
 * belongs to an amendment that is both {@link AmendmentStatus#EFFECTIVE} and already in force
 * ({@code effectiveDate <= reportDate}). Because no amendment can reach {@code EFFECTIVE} in this
 * build (that transition is owned by FOR-06-09), the sum is always zero here, keeping the seam — and
 * the resolved client unit price — behaviour-preserving (R8.2).
 */
@Repository
public interface AmendmentLineDao extends AdminDao<AmendmentLineEntity, Long> {

    /**
     * The sum of {@code extraUnitPrice} across all {@link AmendmentLineEntity}s that (a) target the
     * given {@code workItemId}, (b) belong to an amendment whose {@code status} is {@link
     * AmendmentStatus#EFFECTIVE}, and (c) belong to an amendment whose {@code effectiveDate} is
     * on/before {@code reportDate} (R8.1). Lines with a {@code null} {@code extraUnitPrice}
     * (SCHEDULE_CHANGE lines) contribute nothing. The result is wrapped in {@code COALESCE(..., 0)}
     * so it is never {@code null} — an empty match returns {@link BigDecimal#ZERO}.
     *
     * <p>In this build no amendment can be {@code EFFECTIVE}, so this always returns zero; the query
     * documents the contract FOR-06-09 switches on.
     *
     * @param workItemId the estimate line's work item the report is being valued against
     * @param reportDate the applicable report date (amendments not yet in force are excluded)
     * @return the summed extra unit price, never {@code null} (zero when nothing matches)
     */
    @Query("SELECT COALESCE(SUM(l.extraUnitPrice), 0) FROM AmendmentLineEntity l "
            + "WHERE l.workItem.id = :workItemId "
            + "AND l.amendment.status = com.foremen.dao.model.AmendmentStatus.EFFECTIVE "
            + "AND l.amendment.effectiveDate <= :reportDate")
    BigDecimal sumExtraUnitPriceForEffectiveWorkItem(@Param("workItemId") Long workItemId,
                                                     @Param("reportDate") LocalDate reportDate);
}
