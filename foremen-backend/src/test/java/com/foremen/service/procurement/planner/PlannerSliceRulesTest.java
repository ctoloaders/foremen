package com.foremen.service.procurement.planner;

import static com.foremen.dao.model.PositionStatus.*;
import static com.foremen.service.procurement.planner.PlannerSliceRules.Slice.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.planner.PlannerSliceRules.PositionSnapshot;

/** FOR-11-01 task 2.2: pure side of {@link PlannerSliceRules} (R2.1-R2.5). */
class PlannerSliceRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    private static PositionSnapshot snap(SourcingPath path, PositionStatus status, LocalDate needBy,
                                         LocalDate orderBy, LocalDate planned, long variants) {
        return new PositionSnapshot(path, status, needBy, orderBy, planned, variants, false);
    }

    private static PositionSnapshot snap(PositionStatus status, LocalDate needBy, LocalDate orderBy) {
        return snap(SourcingPath.AGREED, status, needBy, orderBy, null, 0);
    }

    @Test
    void todayUsesWarsawDate() {
        // 22:30 UTC on 2026-06-10 is 2026-06-11 00:30 in Warsaw (CEST)
        Clock clock = Clock.fixed(Instant.parse("2026-06-10T22:30:00Z"), ZoneOffset.UTC);
        assertThat(new PlannerSliceRules(clock).today()).isEqualTo(LocalDate.of(2026, 6, 11));
    }

    @Test
    void siteDeliveryDatePrefersPlannedDelivery() {
        var s = snap(SourcingPath.AGREED, ACCEPTED, TODAY, null, TODAY.plusDays(3), 0);
        assertThat(PlannerSliceRules.siteDeliveryDate(s)).isEqualTo(TODAY.plusDays(3));
        assertThat(PlannerSliceRules.siteDeliveryDate(snap(ACCEPTED, TODAY, null))).isEqualTo(TODAY);
    }

    @Test
    void lateByDeliveryDate() {
        assertThat(PlannerSliceRules.isLate(snap(ORDERED, TODAY.minusDays(1), null), TODAY)).isTrue();
        assertThat(PlannerSliceRules.isLate(snap(ORDERED, TODAY, null), TODAY)).isFalse();
    }

    @Test
    void lateByOrderDateOnlyBeforeOrdered() {
        assertThat(PlannerSliceRules.isLate(snap(WITH_BUYER, null, TODAY.minusDays(1)), TODAY)).isTrue();
        assertThat(PlannerSliceRules.isLate(snap(ORDERED, null, TODAY.minusDays(1)), TODAY)).isFalse();
    }

    @Test
    void terminalIsNeverLate() {
        assertThat(PlannerSliceRules.isLate(snap(RECEIVED, TODAY.minusDays(9), TODAY.minusDays(9)), TODAY)).isFalse();
        assertThat(PlannerSliceRules.isLate(snap(CANCELLED, TODAY.minusDays(9), TODAY.minusDays(9)), TODAY)).isFalse();
    }

    @Test
    void noDatesIsNotLate() {
        assertThat(PlannerSliceRules.isLate(snap(PLANNED, null, null), TODAY)).isFalse();
    }

    @Test
    void urgentWindowIsInclusiveAndOnlyBeforeOrdered() {
        assertThat(PlannerSliceRules.isUrgent(snap(ACCEPTED, TODAY.plusDays(14), null), TODAY, 14)).isTrue();
        assertThat(PlannerSliceRules.isUrgent(snap(ACCEPTED, TODAY.plusDays(15), null), TODAY, 14)).isFalse();
        assertThat(PlannerSliceRules.isUrgent(snap(ORDERED, TODAY.plusDays(1), null), TODAY, 14)).isFalse();
        assertThat(PlannerSliceRules.isUrgent(snap(RECEIVED, TODAY, null), TODAY, 14)).isFalse();
        assertThat(PlannerSliceRules.isUrgent(snap(PLANNED, null, null), TODAY, 14)).isFalse();
    }

    @Test
    void lateBeforeOrderedIsUrgent() {
        assertThat(PlannerSliceRules.isUrgent(snap(PLANNED, TODAY.minusDays(30), null), TODAY, 1)).isTrue();
    }

    @Test
    void onScheduleAndLateAreComplementaryForNonTerminal() {
        var late = snap(ACCEPTED, TODAY.minusDays(1), null);
        var ok = snap(ACCEPTED, TODAY.plusDays(1), null);
        var done = snap(RECEIVED, TODAY.minusDays(1), null);
        assertThat(PlannerSliceRules.slice(late, TODAY)).contains(LATE).doesNotContain(ON_SCHEDULE);
        assertThat(PlannerSliceRules.slice(ok, TODAY)).contains(ON_SCHEDULE).doesNotContain(LATE);
        assertThat(PlannerSliceRules.slice(done, TODAY)).doesNotContain(ON_SCHEDULE, LATE);
    }

    @Test
    void changedNeedsVariants() {
        var changed = snap(SourcingPath.AGREED, PROPOSED, null, null, null, 2);
        assertThat(PlannerSliceRules.slice(changed, TODAY)).contains(CHANGED);
        assertThat(PlannerSliceRules.slice(snap(PROPOSED, null, null), TODAY)).doesNotContain(CHANGED);
    }

    @Test
    void paidAndNotPaidOnlyForAgreedAndNotCancelled() {
        assertThat(PlannerSliceRules.slice(snap(CLIENT_PAID, null, null), TODAY)).contains(PAID).doesNotContain(NOT_PAID);
        assertThat(PlannerSliceRules.slice(snap(RECEIVED, null, null), TODAY)).contains(PAID);
        assertThat(PlannerSliceRules.slice(snap(IN_PROFORMA, null, null), TODAY)).contains(NOT_PAID).doesNotContain(PAID);
        assertThat(PlannerSliceRules.slice(snap(CANCELLED, null, null), TODAY)).doesNotContain(PAID, NOT_PAID);
        for (SourcingPath path : new SourcingPath[] {SourcingPath.PACKAGE, SourcingPath.CLIENT_OWN}) {
            var s = snap(path, WITH_BUYER, null, null, null, 0);
            assertThat(PlannerSliceRules.slice(s, TODAY)).doesNotContain(PAID, NOT_PAID);
        }
    }

    @Test
    void paidAndNotPaidPartitionEveryAgreedNonCancelledStage() {
        for (PositionStatus st : PositionStatus.values()) {
            if (st == CANCELLED) {
                continue;
            }
            var s = snap(st, null, null);
            assertThat(PlannerSliceRules.inSlice(PAID, s, TODAY) ^ PlannerSliceRules.inSlice(NOT_PAID, s, TODAY))
                    .as(st.name()).isTrue();
        }
    }

    @Test
    void receivedUnpaidIsNotTerminalAndIsNotPaid() {
        var unpaid = new PositionSnapshot(SourcingPath.AGREED, RECEIVED, TODAY.minusDays(1), null, null, 0, true);
        assertThat(PlannerSliceRules.isTerminal(unpaid)).isFalse();
        assertThat(PlannerSliceRules.isLate(unpaid, TODAY)).isTrue();
        assertThat(PlannerSliceRules.slice(unpaid, TODAY)).contains(NOT_PAID, LATE).doesNotContain(PAID, ON_SCHEDULE);
        var paid = new PositionSnapshot(SourcingPath.AGREED, RECEIVED, TODAY.minusDays(1), null, null, 0, false);
        assertThat(PlannerSliceRules.isTerminal(paid)).isTrue();
        assertThat(PlannerSliceRules.slice(paid, TODAY)).contains(PAID).doesNotContain(NOT_PAID, LATE);
    }
    @Test
    void allSliceAlwaysMatches() {
        for (PositionStatus st : PositionStatus.values()) {
            assertThat(PlannerSliceRules.inSlice(ALL, snap(st, null, null), TODAY)).isTrue();
        }
    }
}
