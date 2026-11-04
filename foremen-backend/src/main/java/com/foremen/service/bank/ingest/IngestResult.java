package com.foremen.service.bank.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * FOR-07-04 (Requirements 5.3, 6.2, 6.3, 6.4) — counters of one {@link BankTransactionIngestService}
 * call, mergeable across pages / accounts.
 *
 * @param inserted          POSTED lines inserted for the first time
 * @param promoted          PENDING lines promoted in place to POSTED (same {@code externalId})
 * @param updated           already POSTED lines whose mutable provider fields changed on re-delivery
 * @param pendingInserted   PENDING lines inserted by a pending replacement
 * @param pendingUpdated    PENDING lines re-delivered with changed fields
 * @param pendingDeleted    stale PENDING lines deleted by a pending replacement
 * @param skippedDuplicate  rows flagged as duplicates by the provider, or repeated within one batch
 * @param skippedZeroAmount rows with a zero amount (DB CHECK {@code amount <> 0})
 * @param skippedMalformed  rows without the mandatory fields (incl. the connector's own count)
 * @param newPostedIds      ids of the lines that became POSTED in this call (inserted + promoted) —
 *                          the input of detection
 */
public record IngestResult(
        int inserted,
        int promoted,
        int updated,
        int pendingInserted,
        int pendingUpdated,
        int pendingDeleted,
        int skippedDuplicate,
        int skippedZeroAmount,
        int skippedMalformed,
        List<Long> newPostedIds) {

    public static final IngestResult EMPTY = new IngestResult(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());

    public IngestResult {
        newPostedIds = newPostedIds == null ? List.of() : List.copyOf(newPostedIds);
    }

    /** {@code transactions_new}: lines that became POSTED in this call. */
    public int transactionsNew() {
        return inserted + promoted;
    }

    /** {@code transactions_updated}: re-delivered POSTED lines with changed provider fields. */
    public int transactionsUpdated() {
        return updated;
    }

    /** {@code pending_replaced}: PENDING lines written or removed by the pending replacement. */
    public int pendingReplaced() {
        return pendingInserted + pendingDeleted;
    }

    /** {@code rows_skipped}: provider duplicates, zero amounts and malformed rows. */
    public int rowsSkipped() {
        return skippedDuplicate + skippedZeroAmount + skippedMalformed;
    }

    /** Sums the counters and concatenates the new POSTED ids. */
    public IngestResult plus(IngestResult other) {
        if (other == null) {
            return this;
        }
        List<Long> ids = new ArrayList<>(newPostedIds);
        ids.addAll(other.newPostedIds);
        return new IngestResult(
                inserted + other.inserted,
                promoted + other.promoted,
                updated + other.updated,
                pendingInserted + other.pendingInserted,
                pendingUpdated + other.pendingUpdated,
                pendingDeleted + other.pendingDeleted,
                skippedDuplicate + other.skippedDuplicate,
                skippedZeroAmount + other.skippedZeroAmount,
                skippedMalformed + other.skippedMalformed,
                ids);
    }
}
