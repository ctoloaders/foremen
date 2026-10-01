package com.foremen.dao.model;

/**
 * Scope at which an {@code OfferDiscount} (or a negotiation proposition) applies (FOR-05-07,
 * Requirements 2.5, 4.9, 10.16).
 *
 * <p>A {@link #GLOBAL} discount covers the whole offer, a {@link #CATEGORY} discount covers all
 * lines in a work-type group, and a {@link #LINE} discount covers a single estimate line. The
 * scope drives the deterministic <b>override-and-cancel</b> resolution: for any line the single
 * surviving discount is the one at the highest scope covering it — {@code GLOBAL} supersedes
 * {@code CATEGORY} supersedes {@code LINE} — resolved by {@code DiscountResolver} independently of
 * insertion order.
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback.
 */
public enum DiscountScope {
    GLOBAL("Общая", "Globalny"),
    CATEGORY("По категории", "Kategoria"),
    LINE("По позиции", "Pozycja");

    private final String nameRU;
    private final String namePL;

    DiscountScope(String nameRU, String namePL) {
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
     * untranslated identifier is surfaced to the user.
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
