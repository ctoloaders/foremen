package com.foremen.service.procurement.planner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.PlannerRowDto.WorkCategoryCandidate;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Source;

/**
 * Read endpoints behind the position form's work-category link (FOR-11-01 R5.2, R5.10): the
 * scheduled-categories dropdown and the automatic-resolution preview. Both are scoped by project
 * access; an inaccessible or unknown project is a 404.
 */
@Service
public class PlannerWorkLinkService {

    static final int DROPDOWN_LIMIT = 50;
    private static final String ENTITY_NOT_FOUND = "error.entity.not.found";

    /** One option of the scheduled-categories dropdown. */
    public record ScheduledCategoryDto(Long id, String name, LocalDate startDate) {
    }

    /** Automatic resolution of the work link for a material in a project (R5.10). */
    public record WorkLinkPreview(Long effectiveWorkCategoryId, String effectiveWorkCategoryName,
                                  Source workCategorySource,
                                  List<WorkCategoryCandidate> workCategoryCandidates,
                                  LocalDate startDate) {
    }

    private final ProcurementProjectAccess projectAccess;
    private final ScheduledCategoryService scheduledCategoryService;
    private final WorkConsumptionLookup consumptionLookup;
    private final WorkCategoryDao workCategoryDao;
    private final FinishingMaterialDao materialDao;
    private final WorkCategoryResolver resolver = new WorkCategoryResolver();

    public PlannerWorkLinkService(ProcurementProjectAccess projectAccess,
                                  ScheduledCategoryService scheduledCategoryService,
                                  WorkConsumptionLookup consumptionLookup,
                                  WorkCategoryDao workCategoryDao,
                                  FinishingMaterialDao materialDao) {
        this.projectAccess = projectAccess;
        this.scheduledCategoryService = scheduledCategoryService;
        this.consumptionLookup = consumptionLookup;
        this.workCategoryDao = workCategoryDao;
        this.materialDao = materialDao;
    }

    /** Scheduled categories of the project, optionally filtered by {@code q}, earliest start first. */
    @Transactional(readOnly = true)
    public List<ScheduledCategoryDto> scheduledCategories(Long projectId, String q, Authentication auth) {
        requireAccess(projectId, auth);
        Map<Long, Scheduled> scheduled = scheduledCategoryService.scheduled(projectId);
        if (scheduled.isEmpty()) {
            return List.of();
        }
        Set<Long> matching = scheduled.keySet();
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            matching = new HashSet<>();
            for (WorkCategoryEntity category : workCategoryDao.findAllById(scheduled.keySet())) {
                if (contains(category.getNameRU(), needle) || contains(category.getNamePL(), needle)) {
                    matching.add(category.getId());
                }
            }
        }
        List<ScheduledCategoryDto> result = new ArrayList<>();
        for (Long id : matching) {
            Scheduled s = scheduled.get(id);
            result.add(new ScheduledCategoryDto(s.categoryId(), s.name(), s.startDate()));
        }
        result.sort(Comparator
                .comparing(ScheduledCategoryDto::startDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(ScheduledCategoryDto::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(ScheduledCategoryDto::id));
        return result.size() > DROPDOWN_LIMIT ? List.copyOf(result.subList(0, DROPDOWN_LIMIT)) : result;
    }

    /** Automatic resolution (no manual override) for a material in a project. */
    @Transactional(readOnly = true)
    public WorkLinkPreview preview(Long projectId, Long materialId, Authentication auth) {
        requireAccess(projectId, auth);
        FinishingMaterialEntity material = materialId == null ? null : materialDao.findById(materialId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, materialId));
        Long typeId = material == null || material.getType() == null ? null : material.getType().getId();
        Map<Long, Scheduled> scheduled = scheduledCategoryService.scheduled(projectId);
        if (typeId == null || scheduled.isEmpty()) {
            return toPreview(new Resolution(null, Source.NONE, List.of()), scheduled);
        }
        Map<Long, Set<Long>> tier1 = consumptionLookup.estimateCategoriesByType(projectId);
        Map<Long, Set<Long>> tier2 = Map.of();
        if (!hasScheduledMatch(tier1.get(typeId), scheduled)) {
            tier2 = consumptionLookup.catalogCategoriesByType(Set.of(typeId));
        }
        return toPreview(resolver.resolve(null, typeId, tier1, tier2, scheduled), scheduled);
    }

    private static boolean hasScheduledMatch(Set<Long> categories, Map<Long, Scheduled> scheduled) {
        if (categories == null) {
            return false;
        }
        for (Long id : categories) {
            if (scheduled.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    private static WorkLinkPreview toPreview(Resolution r, Map<Long, Scheduled> scheduled) {
        if (r.source() == Source.NONE) {
            return new WorkLinkPreview(null, null, Source.NONE, List.of(), null);
        }
        Scheduled hit = scheduled.get(r.categoryId());
        List<WorkCategoryCandidate> candidates = new ArrayList<>();
        for (Scheduled c : r.candidates()) {
            candidates.add(new WorkCategoryCandidate(c.categoryId(), c.name(), c.startDate()));
        }
        return new WorkLinkPreview(r.categoryId(), hit == null ? null : hit.name(), r.source(),
                List.copyOf(candidates), hit == null ? null : hit.startDate());
    }

    private void requireAccess(Long projectId, Authentication auth) {
        if (projectId == null || auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        if (hasRole(auth, "ADMIN")) {
            return;
        }
        Long userId = userId(auth);
        Set<Long> allowed = userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Long userId(Authentication auth) {
        try {
            return auth.getName() == null ? null : Long.parseLong(auth.getName().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasRole(Authentication auth, String code) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (("ROLE_" + code).equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
