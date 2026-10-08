package com.foremen.service.signing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;

/**
 * Pure collaborator computing a document's signing progress (FOR-05-08, Requirements 4.5, 9.4;
 * design §Components {@code SigningProgressCalculator}).
 *
 * <p>Given the full signature set of a {@code SignableDocument}, it reports how many signatures are
 * {@code SIGNED}, the total number of signatures, which signers are still outstanding, and the
 * derived {@code allSigned} flag that drives both the document's derived
 * {@link com.foremen.dao.model.DocumentStatus#SIGNED} state (parent Property 16) and the UI progress
 * indicator. The invariant is:
 *
 * <pre>{@code
 *   allSigned == (signedCount == totalCount && totalCount > 0)
 * }</pre>
 *
 * so an empty signature set is <strong>never</strong> all-signed (a document with no designated
 * signers is not "fully signed").
 *
 * <p>This component is <strong>pure</strong>: it performs no persistence, holds no state, and its
 * result depends only on the passed-in signature list (Property 5 — "signing progress is a pure
 * function of the signature set"). It is a Spring {@code @Component} only so services can inject it;
 * the whole calculation is also available through the {@code static} {@link #compute(List)} core,
 * which is what the property test exercises.
 */
@Component
public class SigningProgressCalculator {

    /**
     * Computes the {@link SigningProgress} for a document's signatures.
     *
     * @param signatures the document's full signature set; may be empty but must not be {@code null}
     *                   (individual elements must be non-null)
     * @return the progress snapshot; {@link SigningProgress#allSigned()} is {@code true} iff every
     *         signature is {@code SIGNED} and at least one signature exists
     * @throws IllegalArgumentException if {@code signatures} is {@code null}
     */
    public SigningProgress progress(List<DocumentSignatureEntity> signatures) {
        return compute(signatures);
    }

    /**
     * Pure static core of {@link #progress(List)}; see that method for semantics.
     */
    public static SigningProgress compute(List<DocumentSignatureEntity> signatures) {
        if (signatures == null) {
            throw new IllegalArgumentException("signatures must not be null");
        }

        int totalCount = signatures.size();
        int signedCount = 0;
        List<String> outstanding = new ArrayList<>();

        for (DocumentSignatureEntity signature : signatures) {
            if (signature == null) {
                throw new IllegalArgumentException("signatures must not contain null elements");
            }
            if (signature.getStatus() == SignatureStatus.SIGNED) {
                signedCount++;
            } else {
                outstanding.add(signerRef(signature));
            }
        }

        boolean allSigned = signedCount == totalCount && totalCount > 0;
        return new SigningProgress(signedCount, totalCount, allSigned, outstanding);
    }

    /**
     * A stable reference to a signature's designated signer, for the {@code outstanding} list:
     * the explicit {@code signerUser} id when set, otherwise the {@code signerRole} (a null explicit
     * signer is resolved by role, e.g. {@code CLIENT}; R4.3 / decision 8). Falls back to the
     * signature's own id so an outstanding signer is always identifiable.
     */
    private static String signerRef(DocumentSignatureEntity signature) {
        UserEntity signerUser = signature.getSignerUser();
        if (signerUser != null && signerUser.getId() != null) {
            return "user:" + signerUser.getId();
        }
        String signerRole = signature.getSignerRole();
        if (signerRole != null && !signerRole.isBlank()) {
            return "role:" + signerRole;
        }
        return "signature:" + signature.getId();
    }
}
