package com.foremen.service.signing;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.exception.ForemenApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * FOR-05-08 (Requirement 1.3): the single source of truth for the legal command transitions of a
 * {@code SignableDocument}'s {@link DocumentStatus} lifecycle.
 *
 * <p>A pure, stateless collaborator — it performs no persistence and holds no state, mirroring the
 * {@code DraftGateGuard} convention — so it is cheaply property-testable over every
 * {@code (DocumentStatus, DocumentAction)} pair (design key decision 3; Property 2).
 *
 * <p>The <b>only</b> legal command edges are (design §Signing lifecycle, Requirement 1.3):
 *
 * <ul>
 *   <li>{@code DRAFT} + {@link DocumentAction#REQUEST_SIGNATURES} → {@code PENDING_SIGNATURES}</li>
 *   <li>{@code DRAFT} + {@link DocumentAction#VOID} → {@code VOID}</li>
 *   <li>{@code PENDING_SIGNATURES} + {@link DocumentAction#VOID} → {@code VOID}</li>
 * </ul>
 *
 * <p>{@link DocumentStatus#SIGNED} is <b>never</b> a direct target of a command — it is derived from
 * the signature set ({@code recomputeSignedState}), not produced here (Requirements 1.4, 6.6).
 * {@link DocumentStatus#SIGNED} and {@link DocumentStatus#VOID} are terminal: they have no exit, so
 * every command applied to them is illegal. Any undefined transition throws {@code 409
 * error.document.illegal.transition}.
 */
@Component
public class DocumentStatusMachine {

    static final String ILLEGAL_TRANSITION_MESSAGE = "error.document.illegal.transition";

    /**
     * The complete legal command transition table. A {@code (current, action)} pair absent from
     * this map is an illegal transition. {@code SIGNED} and {@code VOID} appear nowhere as a source,
     * encoding their terminal nature; no entry targets {@code SIGNED}, encoding that it is derived.
     */
    private static final Map<DocumentStatus, Map<DocumentAction, DocumentStatus>> TRANSITIONS =
            Map.of(
                    DocumentStatus.DRAFT,
                    Map.of(
                            DocumentAction.REQUEST_SIGNATURES, DocumentStatus.PENDING_SIGNATURES,
                            DocumentAction.VOID, DocumentStatus.VOID),
                    DocumentStatus.PENDING_SIGNATURES,
                    Map.of(DocumentAction.VOID, DocumentStatus.VOID));

    /**
     * Resolves the next status for applying {@code action} to a document currently in
     * {@code current}.
     *
     * @param current the document's current lifecycle status
     * @param action  the lifecycle command being applied
     * @return the resulting next status for a legal {@code (current, action)} edge
     * @throws ForemenApiException 409 {@code error.document.illegal.transition} when the pair is not
     *                             one of the three legal edges (including any command on a terminal
     *                             {@code SIGNED}/{@code VOID} state, or any attempt to reach
     *                             {@code SIGNED} directly) (Requirement 1.3)
     */
    public DocumentStatus transition(DocumentStatus current, DocumentAction action) {
        DocumentStatus next =
                TRANSITIONS.getOrDefault(current, Map.of()).get(action);
        if (next == null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
        }
        return next;
    }
}
