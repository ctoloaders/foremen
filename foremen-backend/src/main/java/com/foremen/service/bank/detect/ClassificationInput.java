package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * FOR-07-04 (Requirement 15.2, 28.3) — the data sent to the AI classifier. Contains only the
 * fields allowed by R15.2: no full IBAN, no provider payload, no user emails.
 *
 * @param direction             {@code INCOME} or {@code EXPENSE}
 * @param amount                absolute amount
 * @param currency              ISO currency code
 * @param bookingDate           booking date
 * @param title                 transfer title
 * @param counterpartyName      counterparty name
 * @param counterpartyIbanLast4 the counterparty IBAN masked to its last 4 characters
 * @param bankName              the bank name
 * @param candidates            rule-matcher candidates (opaque refs + human labels)
 * @param categories            active unplanned-expense categories
 * @param projects              active projects
 */
public record ClassificationInput(
        String direction,
        BigDecimal amount,
        String currency,
        LocalDate bookingDate,
        String title,
        String counterpartyName,
        String counterpartyIbanLast4,
        String bankName,
        List<Candidate> candidates,
        List<Category> categories,
        List<Project> projects) {

    public static final String INCOME = "INCOME";
    public static final String EXPENSE = "EXPENSE";

    public ClassificationInput {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        categories = categories == null ? List.of() : List.copyOf(categories);
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    /** A rule-matcher candidate offered to the model. */
    public record Candidate(String candidateRef, String kind, String label, BigDecimal amount,
            LocalDate date) {
    }

    /** An active {@code UnplannedExpenseCategory}. */
    public record Category(String code, String namePl, String nameRu) {
    }

    /** An active project. */
    public record Project(Long id, String name, String city) {
    }

    /**
     * Mask an IBAN to its last 4 alphanumeric characters (R15.2). Returns {@code null} for a blank
     * input and the whole cleaned value when it has 4 characters or fewer.
     */
    public static String maskIbanLast4(String iban) {
        if (iban == null) {
            return null;
        }
        String cleaned = iban.replaceAll("[^A-Za-z0-9]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= 4 ? cleaned : cleaned.substring(cleaned.length() - 4);
    }
}
