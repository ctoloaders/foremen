package com.foremen.service.execution.amendment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.DocumentSignatureEntity;

/**
 * FOR-06-09 (Requirement 3.1) — the pure, persistence-free resolver of an amendment's
 * <em>all-parties-signed date</em>: the calendar date on which the linked
 * {@link com.foremen.dao.model.SignableDocumentEntity} became
 * {@link com.foremen.dao.model.DocumentStatus#SIGNED}, i.e. the moment its last signature completed.
 *
 * <p>The {@code SignableDocument} carries no "fully-signed-at" timestamp; the all-parties-signed date
 * is therefore derived as the maximum {@link DocumentSignatureEntity#getSignedAt()} across the
 * document's signatures (parent Property 16: SIGNED iff every signature SIGNED). Computing it as a
 * pure function of the signature list keeps it directly jqwik-testable without a persistence context
 * (design §{@code AllPartiesSignedDateResolver}; the orchestration service is a thin caller).
 *
 * <p>The resolver is only ever invoked on a document the engine has already promoted to {@code SIGNED}
 * (every signature {@code SIGNED}, every {@code signedAt} set), so its input precondition holds; it
 * asserts that precondition defensively — an empty set or any {@code null} {@code signedAt} is a
 * programming/invariant error, not a user-facing condition, so it fails fast with
 * {@link IllegalArgumentException}.
 */
@Component
public class AllPartiesSignedDateResolver {

    /**
     * The all-parties-signed date = {@code max(signedAt).toLocalDate()} over the document's
     * signatures (Requirement 3.1).
     *
     * @param signatures the linked document's signatures; must be non-empty with every
     *                   {@code signedAt} set (the engine's {@code SIGNED} precondition)
     * @return the latest signature completion date (the date the document became {@code SIGNED})
     * @throws IllegalArgumentException if {@code signatures} is null/empty or any {@code signedAt} is
     *                                  null (defensive precondition check)
     */
    public LocalDate resolve(List<DocumentSignatureEntity> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException(
                    "resolve requires a non-empty signature set (document must be SIGNED)");
        }
        LocalDateTime max = null;
        for (DocumentSignatureEntity signature : signatures) {
            LocalDateTime signedAt =
                    Objects.requireNonNull(signature, "signature").getSignedAt();
            if (signedAt == null) {
                throw new IllegalArgumentException(
                        "resolve requires every signature to have a non-null signedAt (document must be SIGNED)");
            }
            if (max == null || signedAt.isAfter(max)) {
                max = signedAt;
            }
        }
        return max.toLocalDate();
    }
}
