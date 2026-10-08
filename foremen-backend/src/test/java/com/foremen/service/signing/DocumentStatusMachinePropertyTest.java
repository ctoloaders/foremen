package com.foremen.service.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.exception.ForemenApiException;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;

/**
 * Property-based tests for {@link DocumentStatusMachine} (FOR-05-08, Property 2 — "Status machine
 * permits only the legal transitions").
 *
 * <p>{@link DocumentStatusMachine#transition(DocumentStatus, DocumentAction)} is a pure,
 * deterministic function over the finite {@code (DocumentStatus, DocumentAction)} product. The
 * property under test is <b>totality</b>: for <em>every</em> pair, {@code transition} either
 * returns the one legal next state or throws {@code ForemenApiException(409,
 * "error.document.illegal.transition")} — it never returns silently with an invalid/unexpected
 * state and never throws any other error (Requirements 1.3, 1.4, 6.6; design key decision 3).
 *
 * <p>The three legal edges (and only those) are
 * {@code DRAFT + REQUEST_SIGNATURES → PENDING_SIGNATURES}, {@code DRAFT + VOID → VOID}, and
 * {@code PENDING_SIGNATURES + VOID → VOID}. {@link DocumentStatus#SIGNED} is never a target, and
 * {@link DocumentStatus#SIGNED}/{@link DocumentStatus#VOID} are terminal (no exit). The test
 * recomputes the expected outcome from an independent oracle table rather than reusing the
 * machine's own map.
 *
 * <p>No Spring context and no database — the machine is exercised via {@code new
 * DocumentStatusMachine()} over the enum product.
 *
 * <p>Feature: FOR-05-08-document-signing, Property 2
 *
 * <p><b>Validates: Requirements 1.3, 1.4, 6.6</b>
 */
@Tag("Feature: FOR-05-08-document-signing, Property 2")
class DocumentStatusMachinePropertyTest {

    private static final String ILLEGAL_TRANSITION_MESSAGE = "error.document.illegal.transition";

    private final DocumentStatusMachine machine = new DocumentStatusMachine();

    /**
     * Independent oracle of the three (and only three) legal edges, keyed by {@code current}. Any
     * {@code (current, action)} pair absent here MUST be rejected by the machine. This table is
     * authored by hand from Requirement 1.3 — it is NOT derived from the machine's internal map, so
     * it is a genuine cross-check.
     */
    private static final Map<DocumentStatus, Map<DocumentAction, DocumentStatus>> LEGAL_EDGES =
            Map.of(
                    DocumentStatus.DRAFT,
                    Map.of(
                            DocumentAction.REQUEST_SIGNATURES, DocumentStatus.PENDING_SIGNATURES,
                            DocumentAction.VOID, DocumentStatus.VOID),
                    DocumentStatus.PENDING_SIGNATURES,
                    Map.of(DocumentAction.VOID, DocumentStatus.VOID));

    // ------------------------------------------------------------------------------------------
    // Property 2: totality over the full (status, action) product — every pair yields either the
    // legal next state (matching the independent oracle) or the illegal-transition 409, nothing
    // else.
    // Validates: Requirements 1.3, 1.4, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-08-document-signing, Property 2")
    void transitionIsTotalAndMatchesTheLegalEdgeOracle(
            @ForAll("statuses") DocumentStatus current, @ForAll("actions") DocumentAction action) {
        DocumentStatus expected = LEGAL_EDGES.getOrDefault(current, Map.of()).get(action);

        if (expected != null) {
            // Legal edge: returns exactly the oracle's next state (never SIGNED, which is derived).
            DocumentStatus next = machine.transition(current, action);
            assertThat(next).isEqualTo(expected);
            assertThat(next).isNotEqualTo(DocumentStatus.SIGNED);
        } else {
            // Every other pair (incl. any command on a terminal SIGNED/VOID) throws the 409 with the
            // single illegal-transition message code — not a different status/exception.
            assertThatThrownBy(() -> machine.transition(current, action))
                    .isInstanceOfSatisfying(
                            ForemenApiException.class,
                            ex -> {
                                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                                assertThat(ex.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION_MESSAGE);
                            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 (reinforcement): SIGNED is never produced by the machine for ANY input — it is a
    // derived state, never a direct command target (Requirements 1.4, 6.6).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-08-document-signing, Property 2")
    void signedIsNeverAReachableTargetOfAnyCommand(
            @ForAll("statuses") DocumentStatus current, @ForAll("actions") DocumentAction action) {
        try {
            DocumentStatus next = machine.transition(current, action);
            assertThat(next).isNotEqualTo(DocumentStatus.SIGNED);
        } catch (ForemenApiException ex) {
            // Rejection is an acceptable outcome; it certainly produced no SIGNED state.
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION_MESSAGE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 (reinforcement): terminal states SIGNED and VOID have no exit — every command on
    // them is rejected (Requirement 1.3).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-08-document-signing, Property 2")
    void terminalStatesRejectEveryCommand(
            @ForAll("terminalStatuses") DocumentStatus terminal,
            @ForAll("actions") DocumentAction action) {
        assertThatThrownBy(() -> machine.transition(terminal, action))
                .isInstanceOfSatisfying(
                        ForemenApiException.class,
                        ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ex.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION_MESSAGE);
                        });
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<DocumentStatus> statuses() {
        return Arbitraries.of(DocumentStatus.class);
    }

    @Provide
    Arbitrary<DocumentAction> actions() {
        return Arbitraries.of(DocumentAction.class);
    }

    @Provide
    Arbitrary<DocumentStatus> terminalStatuses() {
        return Arbitraries.of(DocumentStatus.SIGNED, DocumentStatus.VOID);
    }
}
