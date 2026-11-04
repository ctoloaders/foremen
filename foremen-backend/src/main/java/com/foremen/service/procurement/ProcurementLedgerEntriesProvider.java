package com.foremen.service.procurement;

import java.util.List;

/**
 * FOR-07-04 (Requirements 16.4, 18.3, 28.4) — the seam through which the procurement document read
 * model obtains the ledger lines linked ({@code PROCUREMENT}) to a document. The same list feeds the
 * derived {@code Document_Payment_Status} (Σ link amounts vs {@code documentTotal}).
 *
 * <p>{@link BankLinkProcurementLedgerEntriesProvider} is the bank-ledger-backed bean. When no bean is
 * present (or the mapper is instantiated directly in unit tests) the mapper falls back to
 * {@link #NONE}, so {@code ledgerEntries} is always an empty list and the status {@code UNMATCHED}.
 */
@FunctionalInterface
public interface ProcurementLedgerEntriesProvider {

    /** The fallback used while the ledger is absent: no document has linked ledger lines. */
    ProcurementLedgerEntriesProvider NONE = documentId -> List.of();

    /**
     * The ledger lines linked to the document, ordered by booking date. Never {@code null}.
     *
     * @param documentId the document id ({@code null} → empty)
     * @return the linked ledger lines
     */
    List<ProcurementLedgerEntryDto> forDocument(Long documentId);
}
