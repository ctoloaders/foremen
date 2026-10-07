package com.foremen.dao.model;

/**
 * Client-visibility projection of an {@code Offer} (FOR-05-07, Requirements 17.3, 17.4).
 *
 * <p>This is a <b>derived</b> projection of {@link OfferStatus}, never a second, independent state
 * machine: {@code DRAFT → DRAFT}; {@code SENT}/{@code CHANGES_REQUESTED}/{@code COUNTERED →
 * ON_APPROVAL}; {@code APPROVED → APPROVED}; the terminal {@code REJECTED}/{@code WITHDRAWN →
 * CLOSED}. It is the gate that controls whether a CLIENT may see the offer at all — a
 * {@link #DRAFT}-visibility offer is invisible to the client, while a {@link #CLOSED} offer (a dead
 * rejected/withdrawn offer) is still readable by the client who owns it but sits outside the
 * {@link #ON_APPROVAL} negotiable window. The mapping lives in {@code OfferVisibilityResolver} and
 * this enum is never persisted independently of the offer status it is computed from.
 *
 * <p>The enum is stored/rendered as a string. Each value carries a localized {@code nameRU}/{@code
 * namePL} label so no raw key or untranslated identifier is ever surfaced to the user;
 * {@link #label()} resolves the display label with PL fallback.
 */
public enum OfferVisibilityStatus {
    DRAFT("Черновик", "Szkic"),
    ON_APPROVAL("На согласовании", "W uzgodnieniu"),
    APPROVED("Утверждено", "Zatwierdzono"),
    /**
     * Terminal visibility of a dead rejected/withdrawn offer: still readable by the owning client
     * (so the client sees the terminal result of a reject/withdraw), but outside the
     * {@link #ON_APPROVAL} negotiable window.
     */
    CLOSED("Закрыто", "Zamknięte");

    private final String nameRU;
    private final String namePL;

    OfferVisibilityStatus(String nameRU, String namePL) {
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
