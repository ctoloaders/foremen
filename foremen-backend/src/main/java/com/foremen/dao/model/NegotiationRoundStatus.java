package com.foremen.dao.model;

/**
 * Resolution status of a single {@code OfferNegotiationRound} (FOR-05-07, Requirements 4.5, 4.7,
 * 4.9).
 *
 * <p>A round starts {@link #OPEN}. It closes as {@link #ACCEPTED} (client accepted a manager
 * proposal — materialized into applied discounts), {@link #DECLINED} (client declined), or
 * {@link #REJECTED} (manager refused with a reasoned explanation). A still-open narrower-scope round
 * is marked {@link #SUPERSEDED} when a broader-scope proposition overrides it. Acting on any
 * non-{@code OPEN} round is rejected (Requirement 4.7).
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback.
 */
public enum NegotiationRoundStatus {
    OPEN("Открыт", "Otwarty"),
    ACCEPTED("Принят", "Zaakceptowany"),
    DECLINED("Отклонён клиентом", "Odrzucony przez klienta"),
    REJECTED("Отклонён менеджером", "Odrzucony przez menedżera"),
    SUPERSEDED("Заменён", "Zastąpiony");

    private final String nameRU;
    private final String namePL;

    NegotiationRoundStatus(String nameRU, String namePL) {
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
