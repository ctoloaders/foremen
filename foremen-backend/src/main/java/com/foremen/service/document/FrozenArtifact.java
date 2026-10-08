package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 1.5, 3.6): the result of freezing a {@code SignableDocument}'s DRAFT body
 * into its canonical, immutable signing artifact — the object-storage reference of the stored PDF
 * plus its sha-256 content hash.
 *
 * <p>Produced once by {@link PdfFreezeService#freeze} on {@code request-signatures}; the caller
 * writes {@code storageUri} onto {@code SignableDocument.documentUri} and {@code contentHash} onto
 * {@code SignableDocument.contentHash}. From that moment the artifact is byte-immutable: the stored
 * {@code contentHash} is the integrity anchor an {@code ONLINE}/{@code PODPIS_GOV_PL} completion is
 * verified against (R6.4).
 *
 * @param storageUri  the bucket-relative object key of the stored frozen PDF (FOR-12 object storage)
 * @param contentHash the lowercase hex sha-256 of the exact PDF bytes that were stored
 */
public record FrozenArtifact(String storageUri, String contentHash) {
}
