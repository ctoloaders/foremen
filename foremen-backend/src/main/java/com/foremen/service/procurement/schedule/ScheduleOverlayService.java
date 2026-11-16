package com.foremen.service.procurement.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.PurchaseRequestOverdue;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.ScheduledCategoryService;
import com.foremen.service.procurement.planner.WorkCategoryResolver;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkConsumptionLookup;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.Flags;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.ProjectCategory;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-04 (R13, R17.3): read-only materials overlay of the project Gantt. Positions are grouped by the
 * work category they belong to (manual {@code workCategory}, else the automatic resolution of
 * {@link WorkCategoryResolver} over {@link ScheduledCategoryService}); the flags come from
 * {@link MaterialScheduleRiskService}. A fixed number of queries, no money fields.
 */
@Service
public class ScheduleOverlayService {

    static final String NOT_FOUND = "error.entity.not.found";

    private static final Set<ShipmentStatus> CLOSED = EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED);

    /** One position entry of the overlay (R13.3). */
    public record OverlayPosition(Long positionId, String label, PositionStatus status,
                                  LocalDate needByDate, LocalDate orderByDate, LocalDate arrivalDate,
                                  ShipmentStatus shipmentStatus,
                                  boolean orderByMissed, boolean downtimeRisk, boolean noArrivalDate) {
    }

    /** One category row of the overlay. */
    public record OverlayCategory(Long workCategoryId, String code, String name, LocalDate worksStartDate,
                                  List<OverlayPosition> positions) {
    }

    /** The overlay of one project. */
    public record ScheduleOverlayView(Long projectId, LocalDate anchorDate,
                                      List<OverlayCategory> categories, List<OverlayPosition> unlinked) {
    }

    /** Code and display name of a category. */
    record CategoryInfo(String code, String name) {
    }

    private final EntityManager entityManager;
    private final ProjectDao projectDao;
    private final ProcurementProjectAccess projectAccess;
    private final ScheduledCategoryService scheduledCategoryService;
    private final WorkConsumptionLookup consumptionLookup;
    private final MaterialScheduleRiskService riskService;
    private final WorkCategoryResolver resolver = new WorkCategoryResolver();
    private final PurchaseRequestOverdue clockSupport;

    public ScheduleOverlayService(EntityManager entityManager, ProjectDao projectDao,
                                  ProcurementProjectAccess projectAccess,
                                  ScheduledCategoryService scheduledCategoryService,
                                  WorkConsumptionLookup consumptionLookup,
                                  MaterialScheduleRiskService riskService, Clock clock) {
        this.entityManager = entityManager;
        this.projectDao = projectDao;
        this.projectAccess = projectAccess;
        this.scheduledCategoryService = scheduledCategoryService;
        this.consumptionLookup = consumptionLookup;
        this.riskService = riskService;
        this.clockSupport = new PurchaseRequestOverdue(clock);
    }

    @Transactional(readOnly = true)
    public ScheduleOverlayView overlay(Long projectId, Authentication auth) {
        requireAccess(projectId, auth);
        ProjectEntity project = projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId));
        boolean ru = isRussian();
        Map<Long, Scheduled> scheduled = scheduledCategoryService.scheduled(projectId);

        List<PurchasePositionEntity> positions = entityManager.createQuery(
                        "SELECT p FROM PurchasePositionEntity p "
                                + "LEFT JOIN FETCH p.plannedMaterial pm LEFT JOIN FETCH pm.producer LEFT JOIN FETCH pm.type "
                                + "LEFT JOIN FETCH p.actualMaterial am LEFT JOIN FETCH am.producer "
                                + "LEFT JOIN FETCH am.type LEFT JOIN FETCH p.workCategory "
                                + "WHERE p.project.id = :projectId AND p.status <> :cancelled ORDER BY p.id",
                        PurchasePositionEntity.class)
                .setParameter("projectId", projectId)
                .setParameter("cancelled", PositionStatus.CANCELLED)
                .getResultList();

        Map<Long, Long> categoryByPosition = resolveCategories(projectId, positions, scheduled);

        Map<Long, CategoryInfo> extra = new LinkedHashMap<>();
        Set<ProjectCategory> pairs = new HashSet<>();
        for (Long categoryId : scheduled.keySet()) {
            pairs.add(new ProjectCategory(projectId, categoryId));
        }
        for (PurchasePositionEntity p : positions) {
            Long categoryId = categoryByPosition.get(p.getId());
            if (categoryId == null) {
                continue;
            }
            pairs.add(new ProjectCategory(projectId, categoryId));
            WorkCategoryEntity manual = p.getWorkCategory();
            if (!scheduled.containsKey(categoryId) && manual != null) {
                extra.putIfAbsent(categoryId, new CategoryInfo(manual.getCode(),
                        ru ? manual.getNameRU() : manual.getNamePL()));
            }
        }
        Map<ProjectCategory, LocalDate> starts = riskService.worksStarts(pairs);
        Map<Long, LocalDate> startByCategory = new HashMap<>();
        starts.forEach((k, v) -> startByCategory.put(k.workCategoryId(), v));

        Map<Long, CategoryInfo> scheduledInfo = scheduledInfo(scheduled.keySet(), scheduled);
        Map<Long, ShipmentStatus> shipmentStatus = nearestShipmentStatus(positions);
        return assemble(projectId, project.getStartDate(), scheduled.keySet(), scheduledInfo, extra, positions,
                categoryByPosition, startByCategory, shipmentStatus, clockSupport.today());
    }

    /** Codes of the scheduled categories come from the catalog; names from the {@link Scheduled} entry. */
    private Map<Long, CategoryInfo> scheduledInfo(Set<Long> ids, Map<Long, Scheduled> scheduled) {
        Map<Long, CategoryInfo> info = new HashMap<>();
        if (ids.isEmpty()) {
            return info;
        }
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT c.id, c.code FROM WorkCategoryEntity c WHERE c.id IN :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        for (Object[] row : rows) {
            Long id = (Long) row[0];
            info.put(id, new CategoryInfo((String) row[1], scheduled.get(id).name()));
        }
        return info;
    }

    /** Position id to category id; positions without a resolvable category have no entry. */
    private Map<Long, Long> resolveCategories(Long projectId, List<PurchasePositionEntity> positions,
                                               Map<Long, Scheduled> scheduled) {
        Map<Long, Long> result = new HashMap<>();
        Map<Long, Set<Long>> tier1 = Map.of();
        boolean tier1Loaded = false;
        Set<Long> tier2Types = new HashSet<>();
        for (PurchasePositionEntity p : positions) {
            Long typeId = typeId(p);
            if (p.getWorkCategory() != null || typeId == null || scheduled.isEmpty()) {
                continue;
            }
            if (!tier1Loaded) {
                tier1 = consumptionLookup.estimateCategoriesByType(projectId);
                tier1Loaded = true;
            }
            Set<Long> matched = new HashSet<>(tier1.getOrDefault(typeId, Set.of()));
            matched.retainAll(scheduled.keySet());
            if (matched.isEmpty()) {
                tier2Types.add(typeId);
            }
        }
        Map<Long, Set<Long>> tier2 = consumptionLookup.catalogCategoriesByType(tier2Types);
        for (PurchasePositionEntity p : positions) {
            Long manualId = p.getWorkCategory() == null ? null : p.getWorkCategory().getId();
            Resolution resolution = resolver.resolve(manualId, typeId(p), tier1, tier2, scheduled);
            if (resolution.categoryId() != null) {
                result.put(p.getId(), resolution.categoryId());
            }
        }
        return result;
    }

    /** One batch query for the Active_Shipments of the positions, nearest per position (R12.2). */
    private Map<Long, ShipmentStatus> nearestShipmentStatus(List<PurchasePositionEntity> positions) {
        if (positions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = positions.stream().map(PurchasePositionEntity::getId).toList();
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT sl.position.id, s FROM ShipmentLineEntity sl JOIN sl.shipment s "
                                + "WHERE sl.position.id IN :ids AND s.status NOT IN :closed", Object[].class)
                .setParameter("ids", ids)
                .setParameter("closed", CLOSED)
                .getResultList();
        Map<Long, ShipmentEntity> nearest = new HashMap<>();
        for (Object[] row : rows) {
            nearest.merge((Long) row[0], (ShipmentEntity) row[1],
                    (a, b) -> NEAREST.compare(a, b) <= 0 ? a : b);
        }
        Map<Long, ShipmentStatus> result = new HashMap<>();
        nearest.forEach((k, v) -> result.put(k, v.getStatus()));
        return result;
    }

    private static final Comparator<ShipmentEntity> NEAREST = Comparator
            .comparing(ShipmentEntity::getEtaDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(ShipmentEntity::getId);

    /**
     * Pure assembly (R13.1, R13.3 to R13.5). Scheduled categories come first in schedule order, then
     * manual categories that are not scheduled, ordered by name; positions without a category go to
     * {@code unlinked}. Flags are computed by {@link MaterialScheduleRiskService#flags}.
     */
    static ScheduleOverlayView assemble(Long projectId, LocalDate anchor, Set<Long> scheduledOrder,
                                        Map<Long, CategoryInfo> scheduledInfo, Map<Long, CategoryInfo> extra,
                                        List<PurchasePositionEntity> positions, Map<Long, Long> categoryByPosition,
                                        Map<Long, LocalDate> worksStartByCategory,
                                        Map<Long, ShipmentStatus> shipmentStatus, LocalDate today) {
        Map<Long, List<OverlayPosition>> byCategory = new LinkedHashMap<>();
        List<OverlayPosition> unlinked = new ArrayList<>();
        for (PurchasePositionEntity p : positions) {
            if (p.getStatus() == PositionStatus.CANCELLED) {
                continue;
            }
            Long categoryId = categoryByPosition.get(p.getId());
            LocalDate worksStart = categoryId == null ? null : worksStartByCategory.get(categoryId);
            Flags flags = MaterialScheduleRiskService.flags(p, worksStart, today);
            OverlayPosition entry = new OverlayPosition(p.getId(), label(p), p.getStatus(),
                    p.getNeedByDate(), p.getOrderByDate(), p.getPlannedDeliveryDate(),
                    shipmentStatus.get(p.getId()),
                    flags.orderByMissed(), flags.downtimeRisk(), flags.noArrivalDate());
            if (categoryId == null) {
                unlinked.add(entry);
            } else {
                byCategory.computeIfAbsent(categoryId, k -> new ArrayList<>()).add(entry);
            }
        }
        List<OverlayCategory> categories = new ArrayList<>();
        for (Long categoryId : scheduledOrder) {
            CategoryInfo info = scheduledInfo.get(categoryId);
            if (info == null) {
                continue;
            }
            categories.add(new OverlayCategory(categoryId, info.code(), info.name(),
                    worksStartByCategory.get(categoryId), byCategory.getOrDefault(categoryId, List.of())));
        }
        extra.entrySet().stream()
                .filter(e -> !scheduledOrder.contains(e.getKey()))
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(CategoryInfo::name,
                        Comparator.nullsLast(Comparator.<String>naturalOrder()))))
                .forEach(e -> categories.add(new OverlayCategory(e.getKey(), e.getValue().code(),
                        e.getValue().name(), worksStartByCategory.get(e.getKey()),
                        byCategory.getOrDefault(e.getKey(), List.of()))));
        return new ScheduleOverlayView(projectId, anchor, List.copyOf(categories), List.copyOf(unlinked));
    }

    private static String label(PurchasePositionEntity p) {
        FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
        if (m == null) {
            return null;
        }
        String producer = m.getProducer() == null ? null : isRussian()
                ? m.getProducer().getNameRU() : m.getProducer().getNamePL();
        return producer == null || producer.isBlank() ? m.getModel() : m.getModel() + " (" + producer + ")";
    }

    private static Long typeId(PurchasePositionEntity p) {
        FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
        return m == null || m.getType() == null ? null : m.getType().getId();
    }

    private void requireAccess(Long projectId, Authentication auth) {
        if (projectId == null || auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority())) {
                return;
            }
        }
        Long userId;
        try {
            userId = Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            userId = null;
        }
        Set<Long> allowed = userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId);
        }
    }

    private static boolean isRussian() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
