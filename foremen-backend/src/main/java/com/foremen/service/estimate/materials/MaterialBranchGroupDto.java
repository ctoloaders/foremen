package com.foremen.service.estimate.materials;

import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * A branch group of the Materials tab matrix (FOR-05-05b, design "Read model DTOs"): the material rows
 * of a single {@link ConsumptionBranch} (construction / finishing), matching the kosztorys split
 * (R1.5). Mirrors the frontend branch group {@code { branch, rows }}.
 *
 * @param branch the group's branch
 * @param rows   the branch's material rows
 */
public record MaterialBranchGroupDto(
        ConsumptionBranch branch,
        List<MaterialRowDto> rows) {
}
