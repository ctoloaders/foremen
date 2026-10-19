package com.foremen.service.procurement.matrix;

import java.util.List;

/**
 * FOR-06-05 (design §Data Models, R3.1) — a category group within a branch of the materials matrix:
 * the localized category name ({@code ru → pl → code}) plus its planned-material rows, in the
 * FOR-05-05b ordering. Mirrors the frontend category group.
 *
 * @param category the material type (category) localized name
 * @param rows     the planned-material rows in this category, in FOR-05-05b order
 */
public record MatrixCategoryGroupDto(
        String category,
        List<MatrixRowDto> rows) {
}
