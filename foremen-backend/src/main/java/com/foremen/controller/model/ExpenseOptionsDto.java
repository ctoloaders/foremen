package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-07-03 (Requirement 7.5) - the data the expense upload dialog needs: the projects the caller can
 * upload into, the active expense categories, and (only for callers who also hold
 * {@code BANK_LEDGER/READ}) the cash accounts.
 */
public record ExpenseOptionsDto(
        List<ProjectOption> projects,
        List<CategoryOption> categories,
        boolean canChooseCashAccount,
        List<CashAccountOption> cashAccounts) {

    /** An {@code ACTIVE} project the caller may attach an expense to. */
    public record ProjectOption(Long id, String name) {
    }

    /** An active expense category ({@code UnplannedExpenseCategory}). */
    public record CategoryOption(Long id, String code, String nameRU, String namePL) {
    }

    /** A non-archived cash account. */
    public record CashAccountOption(Long id, String name, String currency, boolean defaultForCash) {
    }
}
