package com.foremen.dao.model;

/**
 * Lifecycle status of an {@code Estimate} (FOR-05-03, Requirements 1.3, 11.1, 11.2).
 *
 * <p>An estimate is created in {@link #DRAFT}, the only stage in which its lines, per-room
 * quantities, and per-package project prices are freely editable (the DRAFT gate, Requirement 7);
 * once past {@code DRAFT} ({@link #PRICED} and beyond) free edits are blocked and subsequent
 * changes go through the amendment procedure owned by FOR-05-08.
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback, mirroring the
 * repo-wide {@code firstNonBlank(namePL, nameRU, code)} convention.
 */
public enum EstimateStatus {
    DRAFT("Черновик", "Szkic"),
    PRICED("Оценён", "Wyceniony"),
    APPROVED("Утверждён", "Zatwierdzony"),
    SIGNED("Подписан", "Podpisany");

    private final String nameRU;
    private final String namePL;

    EstimateStatus(String nameRU, String namePL) {
        this.nameRU = nameRU;
        this.namePL = namePL;
    }

    /** Russian display label. */
    public String getNameRU() {
        return nameRU;
    }

    /** Polish display label. */
    public String getNamePL() {
        return namePL;
    }

    /**
     * Localized display label with PL fallback (PL, then RU, then the enum name), so no raw key or
     * untranslated identifier is surfaced to the user (Requirement 11.3).
     */
    public String label() {
        if (namePL != null && !namePL.isBlank()) {
            return namePL;
        }
        if (nameRU != null && !nameRU.isBlank()) {
            return nameRU;
        }
        return name();
    }
}
