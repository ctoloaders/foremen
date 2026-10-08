package com.foremen.dao.model;

/**
 * FOR-05-08 (Requirement 6.1): the method by which a {@link DocumentSignatureEntity} is completed.
 *
 * <p>{@link #PRINT} and {@link #TABLET_INITIALS} are fully implemented via evidence upload (a scan /
 * tablet-parafka image). {@link #ONLINE} and {@link #PODPIS_GOV_PL} are modeled behind the
 * {@code SignatureProvider} abstraction (a stub drives the status flow without a live QTSP /
 * podpis.gov.pl call).
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend (no DB i18n column, Requirement 12.1).
 */
public enum SignatureMethod {
    PRINT,
    ONLINE,
    PODPIS_GOV_PL,
    TABLET_INITIALS
}
