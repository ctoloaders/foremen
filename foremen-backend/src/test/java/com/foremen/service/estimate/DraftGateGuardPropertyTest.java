package com.foremen.service.estimate;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link DraftGateGuard#assertDraft} — the DRAFT-only free-edit gate
 * (FOR-05-03, Requirements 7.1, 7.2, 7.3; design §6.4).
 *
 * <p>The guard is exercised directly against an in-memory {@link EstimateEntity} — no
 * persistence — so Property 11 is cheap to run over every {@link EstimateStatus} value.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 11: DRAFT gate
 *
 * <p><b>Validates: Requirements 7.1, 7.2, 7.3</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 11: DRAFT gate")
class DraftGateGuardPropertyTest {

    private final DraftGateGuard guard = new DraftGateGuard();

    // ------------------------------------------------------------------------------------------
    // Property 11a: DRAFT is always allowed -- assertDraft never throws when status == DRAFT
    // Validates: Requirement 7.1
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 11: DRAFT gate")
    void draftStatusNeverThrows(@ForAll("draftEstimates") EstimateEntity estimate) {
        guard.assertDraft(estimate);

        assertThat(estimate.getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    // ------------------------------------------------------------------------------------------
    // Property 11b: every non-DRAFT status is rejected with 409 error.estimate.locked
    // Validates: Requirements 7.2, 7.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 11: DRAFT gate")
    void nonDraftStatusAlwaysThrowsLocked(@ForAll("nonDraftEstimates") EstimateEntity estimate) {
        assertThatThrownBy(() -> guard.assertDraft(estimate))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiException = (ForemenApiException) ex;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiException.getMessage()).isEqualTo("error.estimate.locked");
                });
    }

    // ------------------------------------------------------------------------------------------
    // Property 11c: for every EstimateStatus value, the throw/allow decision is exactly
    // "status != DRAFT" -- the two properties above combined, restated over the full enum.
    // Validates: Requirements 7.1, 7.2, 7.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 11: DRAFT gate")
    void throwsIffStatusIsNotDraft(@ForAll("anyStatus") EstimateStatus status) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setStatus(status);

        if (status == EstimateStatus.DRAFT) {
            guard.assertDraft(estimate);
        } else {
            assertThatThrownBy(() -> guard.assertDraft(estimate))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiException = (ForemenApiException) ex;
                        assertThat(apiException.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(apiException.getMessage()).isEqualTo("error.estimate.locked");
                    });
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<EstimateStatus> anyStatus() {
        return Arbitraries.of(EstimateStatus.class);
    }

    @Provide
    Arbitrary<EstimateEntity> draftEstimates() {
        return Arbitraries.just(EstimateStatus.DRAFT).map(this::estimateWithStatus);
    }

    @Provide
    Arbitrary<EstimateEntity> nonDraftEstimates() {
        return Arbitraries.of(EstimateStatus.class)
                .filter(status -> status != EstimateStatus.DRAFT)
                .map(this::estimateWithStatus);
    }

    private EstimateEntity estimateWithStatus(EstimateStatus status) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setStatus(status);
        return estimate;
    }
}
