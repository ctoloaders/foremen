package com.foremen.service.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalDouble;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.foremen.service.schedule.TrajectoryCalculator.TrajectoryStatus;

/**
 * FOR-06-02 (Requirement 4.6; design §7 P5/P6) — boundary tests for the pure
 * {@link TrajectoryCalculator}. The classifier is a deterministic function of
 * {@code (elapsedShare, confirmedCompletion, riskMargin)}, so every rule and its
 * boundaries are tested directly with no Spring context.
 */
class TrajectoryCalculatorPropertyTest {

    private static final double RISK_MARGIN = 0.1;

    @Nested
    @DisplayName("classify (R4.2)")
    class Classify {

        @Test
        @DisplayName("a complete category is always ON_TRACK, even when time is up")
        void completeIsOnTrack() {
            assertThat(TrajectoryCalculator.classify(1.0, 1.0, RISK_MARGIN)).isEqualTo(TrajectoryStatus.ON_TRACK);
            // completion ≥ 1 wins even over an overrun elapsed share.
            assertThat(TrajectoryCalculator.classify(1.5, 1.2, RISK_MARGIN)).isEqualTo(TrajectoryStatus.ON_TRACK);
        }

        @Test
        @DisplayName("time up (elapsed ≥ 1) and not complete → OVERDUE")
        void timeUpNotComplete() {
            assertThat(TrajectoryCalculator.classify(1.0, 0.99, RISK_MARGIN)).isEqualTo(TrajectoryStatus.OVERDUE);
            assertThat(TrajectoryCalculator.classify(1.2, 0.5, RISK_MARGIN)).isEqualTo(TrajectoryStatus.OVERDUE);
        }

        @Test
        @DisplayName("progress trailing elapsed by more than the margin → AT_RISK")
        void trailingBeyondMargin() {
            // elapsed 0.6, completion 0.4 → 0.4 < 0.6 − 0.1 = 0.5 → AT_RISK.
            assertThat(TrajectoryCalculator.classify(0.6, 0.4, RISK_MARGIN)).isEqualTo(TrajectoryStatus.AT_RISK);
        }

        @Test
        @DisplayName("just-on-track: completion exactly at the margin edge stays ON_TRACK")
        void justOnTrackAtMarginEdge() {
            // elapsed 0.6, completion 0.5 → 0.5 < 0.5 is false → ON_TRACK (the strict <).
            assertThat(TrajectoryCalculator.classify(0.6, 0.5, RISK_MARGIN)).isEqualTo(TrajectoryStatus.ON_TRACK);
        }

        @Test
        @DisplayName("just-at-risk: a hair below the margin edge flips to AT_RISK")
        void justAtRiskBelowEdge() {
            assertThat(TrajectoryCalculator.classify(0.6, 0.4999, RISK_MARGIN)).isEqualTo(TrajectoryStatus.AT_RISK);
        }

        @Test
        @DisplayName("keeping pace (completion ≥ elapsed) is ON_TRACK")
        void keepingPace() {
            assertThat(TrajectoryCalculator.classify(0.5, 0.5, RISK_MARGIN)).isEqualTo(TrajectoryStatus.ON_TRACK);
            assertThat(TrajectoryCalculator.classify(0.5, 0.8, RISK_MARGIN)).isEqualTo(TrajectoryStatus.ON_TRACK);
        }

        @Test
        @DisplayName("no calendar anchor (NaN elapsed) is always ON_TRACK — no false alert (R4.1)")
        void noAnchorIsOnTrack() {
            assertThat(TrajectoryCalculator.classify(TrajectoryCalculator.NO_ANCHOR, 0.0, RISK_MARGIN))
                    .isEqualTo(TrajectoryStatus.ON_TRACK);
            assertThat(TrajectoryCalculator.classify(TrajectoryCalculator.NO_ANCHOR, 0.3, RISK_MARGIN))
                    .isEqualTo(TrajectoryStatus.ON_TRACK);
        }

        @Test
        @DisplayName("a zero risk margin flags any trailing progress as AT_RISK")
        void zeroMargin() {
            assertThat(TrajectoryCalculator.classify(0.5, 0.49, 0.0)).isEqualTo(TrajectoryStatus.AT_RISK);
            assertThat(TrajectoryCalculator.classify(0.5, 0.5, 0.0)).isEqualTo(TrajectoryStatus.ON_TRACK);
        }
    }

    @Nested
    @DisplayName("projectFinishDay (R4.4)")
    class ProjectFinish {

        @Test
        @DisplayName("extrapolates the finish by the current pace")
        void extrapolatesByPace() {
            // window [1, 10] (10 days); 50% elapsed, 25% done → pace halves → projected finish
            // 1 + 10 × (0.5 / 0.25) = 1 + 20 = 21.
            OptionalDouble pf = TrajectoryCalculator.projectFinishDay(1, 10, 0.5, 0.25);
            assertThat(pf).isPresent();
            assertThat(pf.getAsDouble()).isEqualTo(21.0);
        }

        @Test
        @DisplayName("on-pace work projects the planned finish")
        void onPaceProjectsPlanned() {
            // 50% elapsed, 50% done → pace 1 → projected finish = start + duration = 1 + 10 = 11.
            OptionalDouble pf = TrajectoryCalculator.projectFinishDay(1, 10, 0.5, 0.5);
            assertThat(pf).hasValue(11.0);
        }

        @Test
        @DisplayName("no projection without confirmed progress")
        void noProgressNoProjection() {
            assertThat(TrajectoryCalculator.projectFinishDay(1, 10, 0.5, 0.0)).isEmpty();
        }

        @Test
        @DisplayName("no projection without a calendar anchor")
        void noAnchorNoProjection() {
            assertThat(TrajectoryCalculator.projectFinishDay(1, 10, TrajectoryCalculator.NO_ANCHOR, 0.5)).isEmpty();
        }

        @Test
        @DisplayName("caps a runaway projection at the horizon")
        void capsAtHorizon() {
            // A near-zero completion would project enormously; it is capped.
            OptionalDouble pf = TrajectoryCalculator.projectFinishDay(1, 3650, 1.0, 0.0001);
            assertThat(pf).isPresent();
            assertThat(pf.getAsDouble()).isEqualTo(TrajectoryCalculator.MAX_PROJECTED_FINISH_DAY);
        }
    }
}
