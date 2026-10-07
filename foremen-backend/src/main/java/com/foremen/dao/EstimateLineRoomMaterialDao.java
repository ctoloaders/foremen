package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;

/**
 * DAO for {@link EstimateLineRoomMaterialEntity} — the estimate's frozen copied-price material line
 * of a cell (FOR-05-05 design §B1, R13/R6/R4). Backs {@code EstimateAssignmentService}, the matrix
 * write orchestrator (design §B4): the generic {@link AdminDao} CRUD surface is sufficient because a
 * material line is always reached through its owning {@code (line, room)} room-qty, which owns it via
 * a cascade + orphan-removal collection ({@code EstimateLineRoomQtyEntity.materials}). Persisting the
 * owning graph therefore cascades to the material rows; this DAO exists to satisfy the
 * {@link ProjectScopedService} CRUD contract (with {@code getProjectIdPath()} resolving through
 * {@code roomQty.line.estimate.project}) and for the material-line-keyed reads used by the
 * add/remove/choose-concrete operations (tasks 5.2/5.3).
 */
@Repository
public interface EstimateLineRoomMaterialDao extends AdminDao<EstimateLineRoomMaterialEntity, Long> {

    /**
     * Loads every material line of the given estimate for one {@link ConsumptionBranch}, eagerly
     * fetching the associations the {@code ClientOfferReadModelAssembler} needs to project the offer
     * finishing-selection surface without a lazy-load per row (no N+1, no
     * {@code LazyInitializationException} once the assembler runs outside the service transaction):
     * the {@code finishingType} (for the localized line name) and the {@code concreteFinishingMaterial}
     * with its {@code material} and {@code producer} (for the composed chosen-product-name
     * {@code "{model|material} · {producer}"}) and its {@code packages}
     * (for the chosen-product-vs-selected-package comparison). Walks the SAME
     * {@code estimate → lines → room-qty rows → material rows} graph the matrix assembler traverses,
     * reached here from the owning {@code roomQty.line.estimate}.
     *
     * <p>Ordered by material-line id so the projected surface is deterministic across reads.
     *
     * @param estimateId the owning estimate id
     * @param branch     the branch to load (the offer finishing surface passes
     *                   {@link ConsumptionBranch#finishing})
     * @return the estimate's material lines of {@code branch}, with the finishing type + chosen
     *         product (and its material and producer) initialized, ordered by id
     */
    @EntityGraph(attributePaths = {
            "finishingType",
            "concreteFinishingMaterial",
            "concreteFinishingMaterial.material",
            "concreteFinishingMaterial.producer",
            "concreteFinishingMaterial.packages"})
    @Query("SELECT m FROM EstimateLineRoomMaterialEntity m "
            + "WHERE m.roomQty.line.estimate.id = :estimateId AND m.branch = :branch "
            + "ORDER BY m.id ASC")
    List<EstimateLineRoomMaterialEntity> findByEstimateIdAndBranch(
            @Param("estimateId") Long estimateId, @Param("branch") ConsumptionBranch branch);
}
