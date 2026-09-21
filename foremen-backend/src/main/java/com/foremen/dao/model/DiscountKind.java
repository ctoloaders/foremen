package com.foremen.dao.model;

/**
 * Kind of discount applied to a per-package project price (FOR-05-03, Requirements 5.1, 11.1, 11.2).
 *
 * <p>A {@link #PERCENT} discount reduces the original unit price by a percentage; an
 * {@link #ABSOLUTE} discount subtracts a fixed amount. The discount is a denormalized placeholder on
 * the per-package project price (the negotiation/approval flow is owned by FOR-05-07); it drives the
 * derived effective {@code unitPrice} (Requirement 5).
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback, mirroring the
 * repo-wide {@code firstNonBlank(namePL, nameRU, code)} convention.
 */
public enum DiscountKind {
    PERCENT("Процент", "Procent"),
    ABSOLUTE("Абсолютная сумма", "Kwota");

    private final String nameRU;
    private final String namePL;

    DiscountKind(String nameRU, String namePL) {
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
