package com.foremen.service.signing;

import com.foremen.exception.ForemenApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * FOR-05-08 (Requirements 8.4, 8.5; design §Components "SigningAuthorizationGuard"): the
 * operation-level guard that makes the coarse CLIENT {@code UPDATE(own)} ABAC grant safe.
 *
 * <p>The ABAC matrix (changeset {@code 145-seed-signable-documents-resource.xml}) is deliberately
 * standard-CRUD-only — the module introduces <b>no</b> new operation, so "signing" is nominally an
 * {@code UPDATE} of the document (R8.2). That coarse bit is too permissive on its own: it would let
 * a CLIENT edit the body, request signatures, void, or delete, all of which also map to
 * {@code UPDATE}/{@code CREATE}/{@code DELETE}. This guard is the second, finer gate layered on top
 * of ABAC — exactly the pattern FOR-05-07 uses for CLIENT confidentiality ({@code NegotiationService}
 * / {@code OfferStatusMachine}): the matrix decides the coarse <i>who</i>, this guard decides the
 * precise <i>who-may-do-what</i>.
 *
 * <p>Policy (design §Key decision 2, §SigningAuthorizationGuard):
 * <ul>
 *   <li>A {@code CLIENT} may <b>only</b> {@link DocumentAction#SIGN sign} /
 *       {@link DocumentAction#DECLINE decline} / {@link DocumentAction#FILL_FORM_FIELDS fill form
 *       fields} and only on <b>their own</b> signature / own form fields; any CLIENT attempt to
 *       {@code create} / {@code generate} / {@code saveBody} / {@code requestSignatures} /
 *       {@code void} / {@code delete} is rejected with {@code 403 error.document.operation.forbidden}
 *       even though those nominally map to {@code UPDATE}/{@code CREATE}/{@code DELETE} (R8.4).</li>
 *   <li>The write actions ({@code create} / {@code generate} / {@code saveBody} /
 *       {@code requestSignatures} / {@code void} / {@code delete}) are restricted to
 *       {@code MANAGER} / {@code ADMIN}, plus {@code FOREMAN} for the protocol / acceptance /
 *       defect-form / works-manager-statement / key-handover document types it owns, keyed by the
 *       {@link com.foremen.dao.model.SignableDocumentTypeEntity#getCode() document type code}
 *       (R8.5).</li>
 * </ul>
 *
 * <p>A pure, stateless Spring {@code @Component} (the {@link DraftGateGuard}-style convention): it
 * holds no state and performs no I/O. The acting user's project role code, the attempted action,
 * the document type code, and the own-ness of the targeted signature / form field are resolved by
 * the caller (the service layer, which already loaded the entity graph) and passed in; this keeps
 * the guard trivially unit-testable with no persistence.
 */
@Component
public class SigningAuthorizationGuard {

    private static final String FORBIDDEN_MESSAGE = "error.document.operation.forbidden";

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String MANAGER_ROLE = "MANAGER";
    private static final String FOREMAN_ROLE = "FOREMAN";
    private static final String CLIENT_ROLE = "CLIENT";

    /**
     * The {@link com.foremen.dao.model.SignableDocumentTypeEntity#getCode() document-type codes}
     * (R2.3) a {@code FOREMAN} owns and may therefore write (create / generate / saveBody /
     * request-signatures / void / delete), beyond read: the handover-to-renovation, works- and
     * room-acceptance protocols, the internal defect form, the works-manager statement, and the
     * key handover (R8.5). Any other type (notably the {@code CONTRACT_*} types) is MANAGER/ADMIN
     * only.
     */
    static final Set<String> FOREMAN_OWNED_TYPE_CODES = Set.of(
            "HANDOVER_TO_RENOVATION",
            "WORKS_ACCEPTANCE",
            "DEFECT_FORM",
            "WORKS_MANAGER_STATEMENT",
            "ROOM_ACCEPTANCE",
            "KEY_HANDOVER");

    /**
     * The operations a caller may attempt against a {@link com.foremen.dao.model.SignableDocumentEntity}.
     *
     * <p>{@link #SIGN}, {@link #DECLINE}, and {@link #FILL_FORM_FIELDS} are the <b>signer</b>
     * actions a CLIENT may perform on their own signature / own form fields; the remainder are the
     * <b>write</b> actions reserved for MANAGER/ADMIN (and FOREMAN for its owned type set).
     */
    public enum DocumentAction {
        /** Complete the caller's own signature (an {@code UPDATE}). CLIENT-allowed on own. */
        SIGN,
        /** Decline the caller's own signature (an {@code UPDATE}). CLIENT-allowed on own. */
        DECLINE,
        /** Fill the caller's own form-field values (an {@code UPDATE}). CLIENT-allowed on own. */
        FILL_FORM_FIELDS,
        /** Create a document (a {@code CREATE}). Write action. */
        CREATE,
        /** Render / re-render the DRAFT body by merge (an {@code UPDATE}). Write action. */
        GENERATE,
        /** Save edited DRAFT body text (an {@code UPDATE}). Write action. */
        SAVE_BODY,
        /** Freeze the PDF and move to {@code PENDING_SIGNATURES} (an {@code UPDATE}). Write action. */
        REQUEST_SIGNATURES,
        /** Void the document (an {@code UPDATE}). Write action. */
        VOID,
        /** Delete the document (a {@code DELETE}). Write action. */
        DELETE
    }

    /** The signer actions a CLIENT may perform, provided they target the CLIENT's own records. */
    private static final Set<DocumentAction> CLIENT_SIGNER_ACTIONS =
            Set.of(DocumentAction.SIGN, DocumentAction.DECLINE, DocumentAction.FILL_FORM_FIELDS);

    /**
     * Asserts the acting caller may perform {@code action} on a document of type
     * {@code documentTypeCode}, rejecting with {@code 403 error.document.operation.forbidden}
     * otherwise.
     *
     * @param actingRoleCode   the acting user's project role code (e.g. {@code CLIENT},
     *                         {@code MANAGER}, {@code FOREMAN}, {@code ADMIN}); may be {@code null}
     *                         for an unresolved/foreign role, which is treated as forbidden
     * @param action           the attempted operation
     * @param documentTypeCode the {@link com.foremen.dao.model.SignableDocumentTypeEntity#getCode()
     *                         type code} of the target document (used only for the FOREMAN owned-type
     *                         check; may be {@code null} when not applicable to the action)
     * @param targetsOwn       for a signer action ({@link DocumentAction#SIGN} /
     *                         {@link DocumentAction#DECLINE} / {@link DocumentAction#FILL_FORM_FIELDS}),
     *                         whether the targeted signature / form field belongs to the acting
     *                         CLIENT; ignored for write actions and non-CLIENT roles
     * @throws ForemenApiException 403 {@code error.document.operation.forbidden} when the acting
     *                             role may not perform {@code action} (R8.4, R8.5)
     */
    public void assertAllowed(
            String actingRoleCode, DocumentAction action, String documentTypeCode, boolean targetsOwn) {
        if (isAllowed(actingRoleCode, action, documentTypeCode, targetsOwn)) {
            return;
        }
        throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
    }

    /**
     * Pure decision function behind {@link #assertAllowed}: {@code true} iff the acting role may
     * perform {@code action}. Exposed for direct testing of the allow/deny matrix.
     *
     * <ul>
     *   <li>{@code ADMIN} / {@code MANAGER}: every action (ADMIN also bypasses ABAC upstream).</li>
     *   <li>{@code FOREMAN}: the signer actions, plus the write actions only for a document whose
     *       type is in {@link #FOREMAN_OWNED_TYPE_CODES} (R8.5).</li>
     *   <li>{@code CLIENT}: only {@link #CLIENT_SIGNER_ACTIONS} and only when {@code targetsOwn};
     *       every write action is denied (R8.4).</li>
     *   <li>any other / {@code null} role: denied.</li>
     * </ul>
     */
    public boolean isAllowed(
            String actingRoleCode, DocumentAction action, String documentTypeCode, boolean targetsOwn) {
        if (action == null) {
            return false;
        }
        if (ADMIN_ROLE.equals(actingRoleCode) || MANAGER_ROLE.equals(actingRoleCode)) {
            return true;
        }
        if (FOREMAN_ROLE.equals(actingRoleCode)) {
            if (isSignerAction(action)) {
                return true;
            }
            // A FOREMAN may write only on its owned document types (R8.5).
            return documentTypeCode != null && FOREMAN_OWNED_TYPE_CODES.contains(documentTypeCode);
        }
        if (CLIENT_ROLE.equals(actingRoleCode)) {
            // A CLIENT may only sign/decline/fill — and only its own records (R8.4).
            return isSignerAction(action) && targetsOwn;
        }
        return false;
    }

    private static boolean isSignerAction(DocumentAction action) {
        return CLIENT_SIGNER_ACTIONS.contains(action);
    }
}
