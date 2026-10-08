package com.foremen.service.document;

import java.time.LocalDateTime;

import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.dao.model.SignatureStatus;

/**
 * FOR-05-08 (Requirement 4.1): the read model of one {@code DocumentSignature} — one designated
 * signer's record on a {@code SignableDocument}. Served inside {@link SignableDocumentDto#signatures}.
 *
 * <p>A document is {@code SIGNED} iff every one of these is {@link SignatureStatus#SIGNED} (parent
 * Property 16). The {@code method}/{@code level}/{@code status} classifiers are enums localized on
 * the frontend (no DB i18n columns, R12.1).
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> client-reachable; carries NO cost/estimate/margin field.
 *
 * @param id            the signature id
 * @param signerUserId  the explicit signer user id, or {@code null} when resolved by role
 * @param signerRole    the signer role (e.g. {@code CLIENT}), or {@code null}
 * @param method        the signing method
 * @param level         the eIDAS level
 * @param status        {@code PENDING} / {@code SIGNED} / {@code DECLINED}
 * @param signedAt      completion timestamp, or {@code null} until signed
 * @param evidenceUri   scan / tablet image / sealed-PDF uri, or {@code null} until signed
 * @param declineReason reason on {@code DECLINED}, or {@code null}
 */
public record DocumentSignatureDto(
        Long id,
        Long signerUserId,
        String signerRole,
        SignatureMethod method,
        SignatureLevel level,
        SignatureStatus status,
        LocalDateTime signedAt,
        String evidenceUri,
        String declineReason) {
}
