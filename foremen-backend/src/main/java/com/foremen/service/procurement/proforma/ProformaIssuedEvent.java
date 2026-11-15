package com.foremen.service.procurement.proforma;

/**
 * FOR-11-03 (R4.8): a proforma was issued (numbered, PDF stored, positions in {@code IN_PROFORMA}).
 * Consumed after commit by reverse matching of unmatched bank lines (R10.3).
 *
 * @param proformaId the issued proforma
 */
public record ProformaIssuedEvent(Long proformaId) {
}
