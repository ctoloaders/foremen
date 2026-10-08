package com.foremen.service.document;

import java.util.List;

/**
 * FOR-05-08 (Requirements 4.5, 9.4): the aggregate signing-progress read model of a
 * {@code SignableDocument}, a pure projection of its signature set produced by
 * {@code SigningProgressCalculator}.
 *
 * <p>{@code allSigned == (signedCount == totalCount && totalCount > 0)}; this derived flag drives the
 * document's derived {@code SIGNED} state and the UI progress bar. {@link #outstanding} lists the
 * signers (role / resolved user) whose {@code DocumentSignature} is still {@code PENDING}.
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> this is a client-reachable payload and carries NO
 * cost/estimate/margin field.
 *
 * @param signedCount how many signatures are {@code SIGNED}
 * @param totalCount  how many signatures exist
 * @param allSigned   {@code true} iff {@code signedCount == totalCount && totalCount > 0}
 * @param outstanding the still-{@code PENDING} signers
 */
public record SigningProgressDto(
        int signedCount,
        int totalCount,
        boolean allSigned,
        List<SignerRefDto> outstanding) {

    /**
     * A lightweight reference to a designated signer, used in the outstanding list. Carries only the
     * identity facts needed by the signing UI — no cost/estimate/margin data (R9.6, R13.3).
     *
     * @param signerUserId the explicit signer user id, or {@code null} when resolved by role
     * @param signerRole   the signer role (e.g. {@code CLIENT}), or {@code null}
     */
    public record SignerRefDto(Long signerUserId, String signerRole) {
    }
}
