package com.foremen.service.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.signing.SigningAuthorizationGuard.DocumentAction;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link SigningAuthorizationGuard} (FOR-05-08 task 3.6; Requirements 8.4, 8.5).
 *
 * <p>Exercises the operation-level allow/deny matrix that layers on top of the coarse ABAC
 * {@code UPDATE(own)} CLIENT grant:
 * <ul>
 *   <li>CLIENT: signer actions only on own records; every write action denied (R8.4).</li>
 *   <li>FOREMAN: signer actions always; write actions only for the owned type set, CONTRACT_* denied
 *       (R8.5).</li>
 *   <li>MANAGER / ADMIN: every action.</li>
 *   <li>null / unknown role: denied.</li>
 * </ul>
 * The guard is a pure stateless component, so it is instantiated directly with no Spring context.
 */
@DisplayName("SigningAuthorizationGuard")
class SigningAuthorizationGuardTest {

    private final SigningAuthorizationGuard guard = new SigningAuthorizationGuard();

    private static final Set<DocumentAction> SIGNER_ACTIONS =
            EnumSet.of(DocumentAction.SIGN, DocumentAction.DECLINE, DocumentAction.FILL_FORM_FIELDS);

    private static final Set<DocumentAction> WRITE_ACTIONS = EnumSet.of(
            DocumentAction.CREATE,
            DocumentAction.GENERATE,
            DocumentAction.SAVE_BODY,
            DocumentAction.REQUEST_SIGNATURES,
            DocumentAction.EDIT_SIGNERS,
            DocumentAction.VOID,
            DocumentAction.DELETE);

    /**
     * The manager-assisted on-site parafka action — its own category: reserved for MANAGER/ADMIN,
     * neither a signer's own-record action nor a FOREMAN-owned write action.
     */
    private static final Set<DocumentAction> ON_BEHALF_ACTIONS = EnumSet.of(DocumentAction.SIGN_ON_BEHALF);

    private static final String CONTRACT_TYPE = "CONTRACT_MAIN";
    private static final String OWNED_TYPE = "WORKS_ACCEPTANCE";

    // --- CLIENT: signer actions only on own records (R8.4) ---

    @Nested
    @DisplayName("CLIENT role")
    class ClientRole {

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"SIGN", "DECLINE", "FILL_FORM_FIELDS"})
        @DisplayName("may perform signer actions on its own records")
        void allowsSignerActionsOnOwn(DocumentAction action) {
            assertThat(guard.isAllowed("CLIENT", action, null, true)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"SIGN", "DECLINE", "FILL_FORM_FIELDS"})
        @DisplayName("may NOT perform signer actions on another party's records")
        void deniesSignerActionsOnOthers(DocumentAction action) {
            assertThat(guard.isAllowed("CLIENT", action, null, false)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"CREATE", "GENERATE", "SAVE_BODY", "REQUEST_SIGNATURES", "EDIT_SIGNERS", "VOID", "DELETE"})
        @DisplayName("may NOT perform any write action, even on its own documents")
        void deniesAllWriteActions(DocumentAction action) {
            assertThat(guard.isAllowed("CLIENT", action, OWNED_TYPE, true)).isFalse();
            assertThat(guard.isAllowed("CLIENT", action, CONTRACT_TYPE, true)).isFalse();
        }
    }

    // --- FOREMAN: signer actions always; write only for owned types (R8.5) ---

    @Nested
    @DisplayName("FOREMAN role")
    class ForemanRole {

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"SIGN", "DECLINE", "FILL_FORM_FIELDS"})
        @DisplayName("may always perform signer actions, regardless of ownership")
        void allowsSignerActions(DocumentAction action) {
            assertThat(guard.isAllowed("FOREMAN", action, null, false)).isTrue();
            assertThat(guard.isAllowed("FOREMAN", action, null, true)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"CREATE", "GENERATE", "SAVE_BODY", "REQUEST_SIGNATURES", "EDIT_SIGNERS", "VOID", "DELETE"})
        @DisplayName("may perform write actions on every owned document type")
        void allowsWriteActionsOnOwnedTypes(DocumentAction action) {
            for (String ownedType : SigningAuthorizationGuard.FOREMAN_OWNED_TYPE_CODES) {
                assertThat(guard.isAllowed("FOREMAN", action, ownedType, false))
                        .as("FOREMAN %s on owned type %s", action, ownedType)
                        .isTrue();
            }
        }

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"CREATE", "GENERATE", "SAVE_BODY", "REQUEST_SIGNATURES", "EDIT_SIGNERS", "VOID", "DELETE"})
        @DisplayName("may NOT perform write actions on CONTRACT_* / non-owned types")
        void deniesWriteActionsOnContractTypes(DocumentAction action) {
            assertThat(guard.isAllowed("FOREMAN", action, CONTRACT_TYPE, false)).isFalse();
            assertThat(guard.isAllowed("FOREMAN", action, "SOME_OTHER_TYPE", false))
                    .isFalse();
        }

        @ParameterizedTest
        @EnumSource(
                value = DocumentAction.class,
                names = {"CREATE", "GENERATE", "SAVE_BODY", "REQUEST_SIGNATURES", "EDIT_SIGNERS", "VOID", "DELETE"})
        @DisplayName("may NOT perform write actions when the type code is null")
        void deniesWriteActionsWhenTypeNull(DocumentAction action) {
            assertThat(guard.isAllowed("FOREMAN", action, null, false)).isFalse();
        }

        @Test
        @DisplayName("owned type set is exactly the six protocol / acceptance / handover types")
        void ownedTypeSetContents() {
            assertThat(SigningAuthorizationGuard.FOREMAN_OWNED_TYPE_CODES)
                    .containsExactlyInAnyOrder(
                            "HANDOVER_TO_RENOVATION",
                            "WORKS_ACCEPTANCE",
                            "DEFECT_FORM",
                            "WORKS_MANAGER_STATEMENT",
                            "ROOM_ACCEPTANCE",
                            "KEY_HANDOVER");
        }
    }

    // --- MANAGER / ADMIN: every action ---

    @Nested
    @DisplayName("MANAGER and ADMIN roles")
    class PrivilegedRoles {

        @ParameterizedTest
        @EnumSource(DocumentAction.class)
        @DisplayName("MANAGER may perform every action, regardless of type or ownership")
        void managerAllowsEveryAction(DocumentAction action) {
            assertThat(guard.isAllowed("MANAGER", action, CONTRACT_TYPE, false)).isTrue();
            assertThat(guard.isAllowed("MANAGER", action, null, false)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(DocumentAction.class)
        @DisplayName("ADMIN may perform every action, regardless of type or ownership")
        void adminAllowsEveryAction(DocumentAction action) {
            assertThat(guard.isAllowed("ADMIN", action, CONTRACT_TYPE, false)).isTrue();
            assertThat(guard.isAllowed("ADMIN", action, null, false)).isTrue();
        }
    }

    // --- null / unknown role: denied ---

    @Nested
    @DisplayName("null / unknown role")
    class UnknownRole {

        @ParameterizedTest
        @EnumSource(DocumentAction.class)
        @DisplayName("a null role is denied every action")
        void nullRoleDenied(DocumentAction action) {
            assertThat(guard.isAllowed(null, action, OWNED_TYPE, true)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"WORKER", "FINANCIER", "ESTIMATOR", "", "client", "admin"})
        @DisplayName("an unknown or wrong-case role is denied every action")
        void unknownRoleDenied(String role) {
            for (DocumentAction action : DocumentAction.values()) {
                assertThat(guard.isAllowed(role, action, OWNED_TYPE, true))
                        .as("role %s action %s", role, action)
                        .isFalse();
            }
        }
    }

    // --- null action is always denied ---

    @Test
    @DisplayName("a null action is denied for every role")
    void nullActionDenied() {
        assertThat(guard.isAllowed("ADMIN", null, OWNED_TYPE, true)).isFalse();
        assertThat(guard.isAllowed("MANAGER", null, OWNED_TYPE, true)).isFalse();
        assertThat(guard.isAllowed("FOREMAN", null, OWNED_TYPE, true)).isFalse();
        assertThat(guard.isAllowed("CLIENT", null, OWNED_TYPE, true)).isFalse();
    }

    // --- assertAllowed: throws 403 error.document.operation.forbidden when denied ---

    @Nested
    @DisplayName("assertAllowed")
    class AssertAllowed {

        @Test
        @DisplayName("returns normally when the action is allowed")
        void returnsWhenAllowed() {
            assertThatCode(() ->
                            guard.assertAllowed("CLIENT", DocumentAction.SIGN, null, true))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                            guard.assertAllowed("ADMIN", DocumentAction.DELETE, CONTRACT_TYPE, false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws 403 error.document.operation.forbidden when a CLIENT write is denied")
        void throwsForbiddenForClientWrite() {
            assertThatThrownBy(() ->
                            guard.assertAllowed("CLIENT", DocumentAction.CREATE, OWNED_TYPE, true))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getMessageCode()).isEqualTo("error.document.operation.forbidden");
                    });
        }

        @Test
        @DisplayName("throws 403 when a FOREMAN writes a CONTRACT_* type")
        void throwsForbiddenForForemanContractWrite() {
            assertThatThrownBy(() ->
                            guard.assertAllowed("FOREMAN", DocumentAction.VOID, CONTRACT_TYPE, false))
                    .isInstanceOf(ForemenApiException.class)
                    .extracting(ex -> ((ForemenApiException) ex).getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("throws 403 when a CLIENT signs another party's signature")
        void throwsForbiddenForClientSigningOther() {
            assertThatThrownBy(() ->
                            guard.assertAllowed("CLIENT", DocumentAction.SIGN, null, false))
                    .isInstanceOf(ForemenApiException.class);
        }
    }

    // --- cross-cutting coverage sanity: every action is categorized ---

    @Test
    @DisplayName("every DocumentAction is exactly one of signer / write / on-behalf")
    void everyActionCategorized() {
        for (DocumentAction action : DocumentAction.values()) {
            int categories = (SIGNER_ACTIONS.contains(action) ? 1 : 0)
                    + (WRITE_ACTIONS.contains(action) ? 1 : 0)
                    + (ON_BEHALF_ACTIONS.contains(action) ? 1 : 0);
            assertThat(categories)
                    .as("action %s must be in exactly one category", action)
                    .isEqualTo(1);
        }
    }

    // --- SIGN_ON_BEHALF: MANAGER/ADMIN only (manager-assisted on-site parafka) ---

    @Nested
    @DisplayName("SIGN_ON_BEHALF action (manager-assisted on-site parafka)")
    class SignOnBehalfAction {

        @Test
        @DisplayName("MANAGER and ADMIN may sign on behalf, regardless of type")
        void privilegedRolesAllowed() {
            assertThat(guard.isAllowed("MANAGER", DocumentAction.SIGN_ON_BEHALF, CONTRACT_TYPE, false)).isTrue();
            assertThat(guard.isAllowed("ADMIN", DocumentAction.SIGN_ON_BEHALF, null, false)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"FOREMAN", "CLIENT", "WORKER", "FINANCIER"})
        @DisplayName("no other role may sign on behalf, even on an owned type")
        void otherRolesDenied(String role) {
            assertThat(guard.isAllowed(role, DocumentAction.SIGN_ON_BEHALF, OWNED_TYPE, false)).isFalse();
            assertThat(guard.isAllowed(role, DocumentAction.SIGN_ON_BEHALF, OWNED_TYPE, true)).isFalse();
        }

        @Test
        @DisplayName("a null role is denied signing on behalf")
        void nullRoleDenied() {
            assertThat(guard.isAllowed(null, DocumentAction.SIGN_ON_BEHALF, OWNED_TYPE, false)).isFalse();
        }
    }
}
