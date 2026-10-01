package com.foremen.dao.model;

/**
 * Lifecycle status of an {@code Offer} (FOR-05-07, Requirements 3.1, 3.2, 3.5).
 *
 * <p>An offer is created in {@link #DRAFT} from a {@code PRICED} estimate, sent to the client
 * ({@link #SENT}), and moves through the two-sided negotiation ({@link #CHANGES_REQUESTED} when the
 * client opens a discount request, {@link #COUNTERED} when the manager proposes a figure) until it
 * reaches one of the three terminal states {@link #APPROVED}, {@link #REJECTED}, or
 * {@link #WITHDRAWN}. A terminal offer is immutable — {@link #isTerminal()} is the single predicate
 * the status machine and every mutator consult to reject further changes (Requirement 3.9).
 *
 * <p>The enum is stored as a string ({@code @Enumerated(EnumType.STRING)}). Each value carries a
 * localized {@code nameRU}/{@code namePL} label so no raw key or untranslated identifier is ever
 * surfaced to the user; {@link #label()} resolves the display label with PL fallback, mirroring the
 * repo-wide {@code firstNonBlank(namePL, nameRU, code)} convention.
 */
public enum OfferStatus {
    DRAFT("Черновик", "Szkic", false),
    SENT("Отправлено", "Wysłano", false),
    CHANGES_REQUESTED("Запрошены изменения", "Zażądano zmian", false),
    COUNTERED("Встречное предложение", "Kontrpropozycja", false),
    APPROVED("Утверждено", "Zatwierdzono", true),
    REJECTED("Отклонено", "Odrzucono", true),
    WITHDRAWN("Отозвано", "Wycofano", true);

    private final String nameRU;
    private final String namePL;
    private final boolean terminal;

    OfferStatus(String nameRU, String namePL, boolean terminal) {
        this.nameRU = nameRU;
        this.namePL = namePL;
        this.terminal = terminal;
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
     * Whether this is a terminal state ({@link #APPROVED}, {@link #REJECTED}, {@link #WITHDRAWN}).
     * A terminal offer is immutable: no discount write, negotiation round, package change, or client
     * finishing-material choice is permitted (Requirement 3.9).
     */
    public boolean isTerminal() {
        return terminal;
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
