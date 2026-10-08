package com.foremen.service.document;

import com.foremen.dao.model.SignatureStatus;

/**
 * FOR-05-08 (Requirements 5.3, 6.4, 13.1): the inbound payload of the provider webhook
 * {@code POST /api/signatures/callback} for {@code ONLINE} / {@code PODPIS_GOV_PL} ceremonies. The
 * webhook is intentionally unauthenticated (like {@code AuthController}) and is validated instead by
 * matching the {@link #providerRef} to a pending signature and verifying the sealed-evidence
 * integrity against the document's stored {@code contentHash} (R6.4) before the signature is marked
 * {@code SIGNED}.
 *
 * @param providerRef  the provider session reference identifying the signature (required)
 * @param outcome      the reported outcome ({@code SIGNED} / {@code DECLINED})
 * @param evidenceUri  the sealed-evidence uri, or {@code null}
 * @param contentHash  the hash reported by the provider, verified against the stored one, or {@code null}
 * @param declineReason the reported decline reason when {@code outcome == DECLINED}, or {@code null}
 */
public record ProviderCallback(
        String providerRef,
        SignatureStatus outcome,
        String evidenceUri,
        String contentHash,
        String declineReason) {
}
