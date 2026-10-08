package com.foremen.service.signing;

import java.util.List;

/**
 * FOR-05-08 (Requirements 4.5, 9.4): the pure result of {@link SigningProgressCalculator#progress},
 * a snapshot of how many of a document's designated signatures are complete.
 *
 * <p>This is a small, persistence-free value produced entirely from a signature set. It mirrors the
 * shape later surfaced to the client as {@code SigningProgressDto}
 * ({@code { signedCount, totalCount, allSigned, outstanding }}); the controller/mapper layer is
 * responsible for projecting it onto the wire DTO. Keeping the calculator's result a plain record
 * lets Property 5 ("signing progress is a pure function of the signature set") be exercised with no
 * JPA/Spring context.
 *
 * <p>The {@code allSigned} flag is the single source of truth for the derived
 * {@link com.foremen.dao.model.DocumentStatus#SIGNED} state (parent Property 16): it is
 * {@code true} iff every signature is {@code SIGNED} <strong>and</strong> at least one signature
 * exists — an empty signature set is never "all signed".
 *
 * @param signedCount  the number of {@code SIGNED} signatures; {@code 0 <= signedCount <= totalCount}
 * @param totalCount   the total number of signatures (designated signers) on the document
 * @param allSigned    {@code signedCount == totalCount && totalCount > 0}
 * @param outstanding  human/stable references to the signers whose signature is not yet
 *                     {@code SIGNED} (i.e. still {@code PENDING} or {@code DECLINED}); size is
 *                     {@code totalCount - signedCount}
 */
public record SigningProgress(int signedCount,
                              int totalCount,
                              boolean allSigned,
                              List<String> outstanding) {

    /** Compact constructor: defensively copy {@code outstanding} so the record stays immutable. */
    public SigningProgress {
        outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }
}
