package com.foremen.dao.model;

/**
 * Kind of a single {@code OfferNegotiationRound} in the two-sided offer thread (FOR-05-07,
 * Requirements 4.1, 4.3, 10.18).
 *
 * <p>A client opens negotiation with a {@link #DISCOUNT_REQUEST} that carries <b>no</b> figure; the
 * manager owns the number, either proposing it via {@link #MANAGER_PROPOSAL} (the only kind that may
 * carry {@code valueKind}/{@code value}) or refusing with a mandatory reasoned
 * {@link #MANAGER_REJECT}. The client then closes the round with {@link #CLIENT_ACCEPT} (materializes
 * the proposal into applied discounts) or {@link #CLIENT_DECLINE}.
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback.
 */
public enum NegotiationRoundKind {
    DISCOUNT_REQUEST("Запрос скидки", "Prośba o rabat"),
    MANAGER_PROPOSAL("Предложение менеджера", "Propozycja menedżera"),
    MANAGER_REJECT("Отказ менеджера", "Odmowa menedżera"),
    CLIENT_ACCEPT("Принято клиентом", "Zaakceptowano przez klienta"),
    CLIENT_DECLINE("Отклонено клиентом", "Odrzucono przez klienta");

    private final String nameRU;
    private final String namePL;

    NegotiationRoundKind(String nameRU, String namePL) {
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
