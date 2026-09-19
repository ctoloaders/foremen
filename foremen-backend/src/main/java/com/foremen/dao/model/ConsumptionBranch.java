package com.foremen.dao.model;

/**
 * The branch of a {@code WorkMaterialConsumption} norm (FOR-04-19): a work item's material
 * consumption is split into a <b>construction</b> branch (analog groups drawn from
 * {@code CONSTRUCTION_MATERIAL_TYPES}, batches from {@code construction_materials}) and a
 * <b>finishing</b> branch (analog groups drawn from the FOR-04-16 {@code material_types}, batches
 * from {@code finishing_materials}).
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}) on the consumption row
 * and drives both the write-path XOR + branch-match validation (exactly one material-type reference
 * must be set, matching the branch) and the read-time analog-batch resolution used by the computed
 * money range (вилка). Rendering always uses localized labels
 * ({@code workMaterialConsumption.branch.construction} / {@code .finishing}), never the raw enum
 * value.
 */
public enum ConsumptionBranch {
    construction,
    finishing
}
