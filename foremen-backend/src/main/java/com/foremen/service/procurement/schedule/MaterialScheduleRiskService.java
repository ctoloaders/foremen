package com.foremen.service.procurement.schedule;

import com.foremen.dao.ProjectScheduleBarDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * FOR-11-04 (R13.3 to R13.5, R13.7): the single place that computes the schedule-risk flags of a
 * purchase position. Reused by the overlay endpoint, the deadline sweep and the ETA events.
 *
 * <p>The flag computation is pure (static methods). {@link #worksStarts} is the loader of the works
 * start per (project, work category): the earliest bar converted by {@link WorksStartResolver}, in one
 * query for all pairs.
 */
@Service
@RequiredArgsConstructor
public class MaterialScheduleRiskService {

    /** A (project, work category) pair. */
    public record ProjectCategory(Long projectId, Long workCategoryId) {
    }

    /** The derived flags of one position. */
    public record Flags(boolean orderByMissed, boolean downtimeRisk, boolean noArrivalDate) {
    }

    private final ProjectScheduleBarDao barDao;

    /**
     * Works start per pair. Only pairs with a bar and a project start date have an entry
     * (R13.4: otherwise the category carries {@code worksStartDate = null}).
     */
    @Transactional(readOnly = true)
    public Map<ProjectCategory, LocalDate> worksStarts(Collection<ProjectCategory> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return Map.of();
        }
        Set<ProjectCategory> wanted = new HashSet<>(pairs);
        Set<Long> projectIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        for (ProjectCategory pair : wanted) {
            projectIds.add(pair.projectId());
            categoryIds.add(pair.workCategoryId());
        }
        Map<ProjectCategory, LocalDate> result = new HashMap<>();
        for (ProjectScheduleBarDao.EarliestBarRow row
                : barDao.findEarliestBarStarts(projectIds, categoryIds)) {
            ProjectCategory key = new ProjectCategory(row.projectId(), row.categoryId());
            if (!wanted.contains(key)) {
                continue;
            }
            LocalDate start = WorksStartResolver.startDate(row.projectStartDate(), row.minStartHalfDays());
            if (start != null) {
                result.put(key, start);
            }
        }
        return result;
    }

    /** Pure core: the three flags of a position against the works start and today. */
    public static Flags flags(PurchasePositionEntity position, LocalDate worksStart, LocalDate today) {
        return new Flags(
                orderByMissed(position.getStatus(), position.getSourcingPath(), position.getOrderByDate(), today),
                downtimeRisk(position.getStatus(), position.getPlannedDeliveryDate(), worksStart),
                noArrivalDate(position.getStatus(), position.getPlannedDeliveryDate()));
    }

    /** {@code orderByDate <= today}, status before {@code ORDERED}, not {@code CLIENT_OWN}. */
    public static boolean orderByMissed(PositionStatus status, SourcingPath path,
                                        LocalDate orderByDate, LocalDate today) {
        return orderByDate != null && today != null && !orderByDate.isAfter(today)
                && status != null && status.ordinal() < PositionStatus.ORDERED.ordinal()
                && path != SourcingPath.CLIENT_OWN;
    }

    /**
     * Downtime risk: {@code eta} is after {@code worksStart}, the position is neither {@code RECEIVED}
     * nor {@code CANCELLED}. {@code false} when either date is unknown.
     */
    public static boolean downtimeRisk(PositionStatus status, LocalDate eta, LocalDate worksStart) {
        return eta != null && worksStart != null && eta.isAfter(worksStart)
                && status != PositionStatus.RECEIVED && status != PositionStatus.CANCELLED;
    }

    /** No arrival date although the position is still expected (not received, not cancelled). */
    public static boolean noArrivalDate(PositionStatus status, LocalDate arrivalDate) {
        return arrivalDate == null
                && status != PositionStatus.RECEIVED && status != PositionStatus.CANCELLED;
    }
}
