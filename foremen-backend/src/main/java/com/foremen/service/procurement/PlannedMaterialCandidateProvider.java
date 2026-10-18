package com.foremen.service.procurement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.service.estimate.materials.MaterialBranchGroupDto;
import com.foremen.service.estimate.materials.MaterialRowDto;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;

/**
 * FOR-06-03 (design §5.4, Requirements 4.1, 4.3, 4.4) — the project's {@code Planned_Material}
 * candidate set for the pure {@link MatchEngine}.
 *
 * <p>A thin reader over the <strong>existing FOR-05-05b concrete-material projection</strong>
 * ({@link MaterialsListService#getMaterials(Long, boolean)} — the Materials tab read model). That
 * projection already yields exactly one row per <em>distinct chosen {@code Concrete_Material}</em>
 * on the project's estimate, grouped by branch, with Placeholders excluded — i.e. the
 * {@code Planned_Material} set. This provider <strong>does not build a second material list</strong>
 * (R4.1); it maps each projected row to a {@link PlannedMaterial} the engine matches against:
 *
 * <ul>
 *   <li>{@code materialId} — the row's concrete material id (the soft FK the matched line stores and
 *       the engine's final tie-break key);</li>
 *   <li>{@code manufacturer} — the material's {@code producer}, resolved {@code ru → pl → code}
 *       (R4.4);</li>
 *   <li>{@code category} — the material {@code type}, resolved {@code ru → pl → code} (R4.4);</li>
 *   <li>{@code names} — the localized names {@code [nameRU, namePL]} (R4.4); the engine already
 *       skips any blank entry while matching.</li>
 * </ul>
 *
 * <p>Because the projection's row carries only a single read-layer-localized {@code materialName}
 * and {@code typeName} (and no {@code producer} at all), the provider re-reads the three match
 * dimensions from the backing material entities (construction vs. finishing id-space is taken from
 * the row's {@link ConsumptionBranch}), so the engine sees both the RU and PL name forms and a
 * manufacturer it otherwise could not. A finishing material's display names live on its associated
 * {@code materials} row, not on the finishing row itself.
 *
 * <p>When the project has no chosen concrete materials the candidate set is <strong>empty</strong>
 * (never an error, R4.2) — the engine maps every extracted line to {@code NONE}/{@code UNMATCHED}.
 */
@Component
public class PlannedMaterialCandidateProvider {

    private final MaterialsListService materialsListService;
    private final ConstructionMaterialDao constructionMaterialDao;
    private final FinishingMaterialDao finishingMaterialDao;

    public PlannedMaterialCandidateProvider(
            MaterialsListService materialsListService,
            ConstructionMaterialDao constructionMaterialDao,
            FinishingMaterialDao finishingMaterialDao) {
        this.materialsListService = materialsListService;
        this.constructionMaterialDao = constructionMaterialDao;
        this.finishingMaterialDao = finishingMaterialDao;
    }

    /**
     * The project's planned-material candidate set, in the FOR-05-05b projection's stable
     * (branch → first-seen) order. Empty when the project has no chosen concrete materials (R4.2).
     *
     * @param projectId the owning project whose estimate's chosen concrete materials to read
     * @return one {@link PlannedMaterial} per distinct chosen concrete material; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<PlannedMaterial> candidatesFor(Long projectId) {
        // Reuse the FOR-05-05b read model — the single source of the distinct chosen concrete
        // materials (Placeholders already excluded). Read-only, DRAFT-independent (editable=false).
        MaterialsListDto materials = materialsListService.getMaterials(projectId, false);
        if (materials == null || materials.branches() == null) {
            return List.of();
        }

        // Partition the projected row ids by branch (construction/finishing are independent id
        // spaces) while remembering each row's position so the output preserves the projection order.
        List<RowRef> rows = new ArrayList<>();
        List<Long> constructionIds = new ArrayList<>();
        List<Long> finishingIds = new ArrayList<>();
        for (MaterialBranchGroupDto group : materials.branches()) {
            if (group == null || group.rows() == null) {
                continue;
            }
            for (MaterialRowDto row : group.rows()) {
                if (row == null || row.materialId() == null) {
                    continue;
                }
                rows.add(new RowRef(group.branch(), row.materialId()));
                if (group.branch() == ConsumptionBranch.finishing) {
                    finishingIds.add(row.materialId());
                } else {
                    constructionIds.add(row.materialId());
                }
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ConstructionMaterialEntity> construction = byId(constructionMaterialDao.findAllById(constructionIds));
        Map<Long, FinishingMaterialEntity> finishing = byId(finishingMaterialDao.findAllById(finishingIds));

        List<PlannedMaterial> candidates = new ArrayList<>(rows.size());
        for (RowRef ref : rows) {
            PlannedMaterial candidate = ref.branch() == ConsumptionBranch.finishing
                    ? finishingCandidate(ref.materialId(), finishing.get(ref.materialId()))
                    : constructionCandidate(ref.materialId(), construction.get(ref.materialId()));
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    // --- per-branch mapping --------------------------------------------------------------------

    private static PlannedMaterial constructionCandidate(Long materialId, ConstructionMaterialEntity entity) {
        if (entity == null) {
            return null;
        }
        ConstructionMaterialTypeEntity type = entity.getType();
        String category = type == null ? null
                : firstNonBlank(type.getNameRU(), type.getNamePL(), type.getCode());
        return new PlannedMaterial(
                materialId,
                producerName(entity.getProducer()),
                category,
                List.of(nullToEmpty(entity.getNameRU()), nullToEmpty(entity.getNamePL())));
    }

    private static PlannedMaterial finishingCandidate(Long materialId, FinishingMaterialEntity entity) {
        if (entity == null) {
            return null;
        }
        MaterialTypeEntity type = entity.getType();
        String category = type == null ? null
                : firstNonBlank(type.getNameRU(), type.getNamePL(), type.getCode());
        // A finishing material's display names come from its associated materials row.
        MaterialEntity material = entity.getMaterial();
        String nameRu = material == null ? null : material.getNameRU();
        String namePl = material == null ? null : material.getNamePL();
        String code = material == null ? null : material.getCode();
        return new PlannedMaterial(
                materialId,
                producerName(entity.getProducer()),
                category,
                List.of(firstNonBlank(nameRu, code), firstNonBlank(namePl, code)));
    }

    /** Producer display name, resolved {@code ru → pl → code} (R4.4); {@code null} when unset. */
    private static String producerName(MaterialProducerEntity producer) {
        return producer == null ? null
                : firstNonBlank(producer.getNameRU(), producer.getNamePL(), producer.getCode());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static <T extends com.foremen.dao.model.BaseEntity> Map<Long, T> byId(Iterable<T> entities) {
        Map<Long, T> byId = new LinkedHashMap<>();
        for (T entity : entities) {
            if (entity != null && entity.getId() != null) {
                byId.put(entity.getId(), entity);
            }
        }
        return byId;
    }

    /** First non-null, non-blank value among the candidates, or {@code null} when none qualifies. */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** One projected row's identity: its branch (id-space) and concrete material id. */
    private record RowRef(ConsumptionBranch branch, Long materialId) {
    }
}
