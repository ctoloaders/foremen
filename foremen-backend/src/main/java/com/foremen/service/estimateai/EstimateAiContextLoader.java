package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;
import com.foremen.service.estimateai.CatalogContextBuilder.CategoryView;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;

/**
 * FOR-05-04 (design B5; Requirements 1.6, 3.4, 5.1) - read side of the {@code PLANNING_WORKS} stage. Loads, in
 * one read-only transaction, the catalog as read at the start of the ESTIMATE phase (no prices in the model
 * context), the saved rooms of the project (the job's own plus the pre-existing ones) and the estimate's
 * applied package. Returns plain records so the worker never touches lazy JPA state.
 */
@Component
public class EstimateAiContextLoader {

    /** Everything the planner and the converter need. */
    public record Context(List<CategoryView> categories, Map<String, CatalogWork> catalog, String roomsText,
                          List<RoomFacts> rooms, BigDecimal roomsArea, String appliedPackageCode,
                          Map<String, Long> categoryIdByCode) {
    }

    private final WorkItemDao workItemDao;
    private final WorkCategoryDao categoryDao;
    private final WorkPriceDao workPriceDao;
    private final RoomDao roomDao;
    private final EstimateDao estimateDao;

    public EstimateAiContextLoader(WorkItemDao workItemDao, WorkCategoryDao categoryDao, WorkPriceDao workPriceDao,
                                   RoomDao roomDao, EstimateDao estimateDao) {
        this.workItemDao = workItemDao;
        this.categoryDao = categoryDao;
        this.workPriceDao = workPriceDao;
        this.roomDao = roomDao;
        this.estimateDao = estimateDao;
    }

    @Transactional(readOnly = true)
    public Context load(Long projectId) {
        List<WorkItemEntity> items = new ArrayList<>();
        workItemDao.findAll().forEach(items::add);
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        if (!items.isEmpty()) {
            for (WorkPriceEntity wp : workPriceDao.findByWorkItemIdIn(
                    items.stream().map(WorkItemEntity::getId).toList())) {
                prices.put(wp.getWorkItem().getId(), wp.getNetPrice());
            }
        }
        Map<String, CatalogWork> catalog = new LinkedHashMap<>();
        for (WorkItemEntity w : items) {
            if (w.getCode() == null) {
                continue;
            }
            String unit = w.getUnit() == null ? null : w.getUnit().getCode();
            catalog.put(w.getCode(), new CatalogWork(w.getId(), w.getCode(),
                    w.getNamePL() != null ? w.getNamePL() : w.getNameRU(), w.isActive(), prices.get(w.getId()),
                    isPercentage(unit),
                    w.getRoomTypes().stream().map(rt -> rt.getId()).collect(Collectors.toSet())));
        }
        // A work without a code cannot be named by the model: it is not part of the catalog context.
        List<CategoryView> categories = CatalogContextBuilder.fromEntities(
                items.stream().filter(w -> w.getCode() != null && !w.getCode().isBlank()).toList());
        Map<String, Long> categoryIds = new LinkedHashMap<>();
        for (WorkCategoryEntity c : categoryDao.findAll()) {
            if (c.getCode() != null) {
                categoryIds.put(c.getCode(), c.getId());
            }
        }

        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        List<RoomFacts> facts = new ArrayList<>();
        BigDecimal area = BigDecimal.ZERO;
        for (RoomEntity r : rooms) {
            facts.add(new RoomFacts(r.getId(), r.getRoomType() == null ? null : r.getRoomType().getId(),
                    r.getFloorArea(), r.getWallArea()));
            if (r.getFloorArea() != null) {
                area = area.add(r.getFloorArea());
            }
        }
        String pkg = estimateDao.findByProjectId(projectId).map(e -> e.getAppliedPackageCode()).orElse(null);
        return new Context(categories, catalog, RoomContextBuilder.build(rooms), facts,
                area.signum() > 0 ? area : null, pkg, categoryIds);
    }

    /**
     * Maps the model's raw {@code coefficients[]} (stated by the user only) to the converter's coefficients,
     * resolving {@code targetCode} to an id. Entries with an unknown scope, target or percent are dropped.
     */
    public static List<PromptCoefficient> coefficients(JsonNode raw, Context ctx) {
        List<PromptCoefficient> out = new ArrayList<>();
        if (raw == null || !raw.isArray()) {
            return out;
        }
        for (JsonNode c : raw) {
            LabourPriceAdjustmentScope scope = scopeOf(c.path("scope").asText(null));
            BigDecimal pct = pctOf(c.get("pct"));
            if (scope == null || pct == null || pct.signum() == 0) {
                continue;
            }
            Long target = null;
            if (scope != LabourPriceAdjustmentScope.PROJECT) {
                String code = c.path("targetCode").asText(null);
                if (code == null || code.isBlank()) {
                    continue;
                }
                target = scope == LabourPriceAdjustmentScope.WORK_ITEM
                        ? (ctx.catalog().get(code.strip()) == null ? null : ctx.catalog().get(code.strip()).id())
                        : ctx.categoryIdByCode().get(code.strip());
                if (target == null) {
                    continue;
                }
            }
            String reason = c.path("reason").asText(null);
            out.add(new PromptCoefficient(scope, target, pct,
                    reason == null || reason.isBlank() ? "Coefficient stated in the prompt." : reason.strip()));
        }
        return out;
    }

    private static LabourPriceAdjustmentScope scopeOf(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LabourPriceAdjustmentScope.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal pctOf(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return null;
        }
        return n.decimalValue();
    }

    /** A catalog unit that is a percentage (for example coordination work), which the engine cannot compute. */
    static boolean isPercentage(String unitCode) {
        if (unitCode == null) {
            return false;
        }
        String u = unitCode.strip().toLowerCase(Locale.ROOT);
        return u.contains("%") || u.equals("percent") || u.equals("pct");
    }
}
