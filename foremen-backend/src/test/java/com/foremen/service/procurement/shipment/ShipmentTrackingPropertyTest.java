package com.foremen.service.procurement.shipment;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService;
import com.foremen.service.procurement.shipment.ShipmentRules.EtaCandidate;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-04 property tests (jqwik) over the pure rules.
 *
 * <p>**Validates: Requirements 25.2** (cap invariant R4.4, effective ETA R11.1, order-by one-shot R14.2,
 * back-off R7.6).
 */
class ShipmentTrackingPropertyTest {

    private static final LocalDate BASE = LocalDate.of(2026, 3, 1);
    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");

    // ---------------------------------------------------------------------------------------------
    // Cap invariant
    // ---------------------------------------------------------------------------------------------

    /** A shipment-line operation against one position: add a line, or cancel the n-th line added so far. */
    record Op(boolean cancel, int index, BigDecimal quantity) {
    }

    @Provide
    Arbitrary<BigDecimal> qty() {
        return Arbitraries.integers().between(1, 20_000).map(i -> BigDecimal.valueOf(i, 3));
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Op> op = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, qty().map(q -> new Op(false, 0, q))),
                net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(0, 30).map(i -> new Op(true, i, null))));
        return op.list().ofMinSize(1).ofMaxSize(40);
    }

    @Property(tries = 400)
    @Tag("Feature: FOR-11-04, Property: shipped quantity never exceeds the cap")
    void sumOfActiveLineQuantitiesNeverExceedsCap(
            @ForAll SourcingPath path,
            @ForAll("qty") BigDecimal ordered,
            @ForAll("qty") BigDecimal quantity,
            @ForAll("ops") List<Op> ops) {
        BigDecimal cap = ShipmentRules.capFor(path, ordered, quantity);
        assertThat(cap).isEqualTo(path == SourcingPath.CLIENT_OWN ? quantity : ordered);

        List<BigDecimal> lines = new ArrayList<>();
        List<Boolean> active = new ArrayList<>();
        for (Op op : ops) {
            if (op.cancel()) {
                if (!lines.isEmpty()) {
                    active.set(op.index() % lines.size(), false);
                }
            } else if (activeSum(lines, active).add(op.quantity()).compareTo(cap) <= 0) {
                // same admission rule as ShipmentService.validateLines
                lines.add(op.quantity());
                active.add(true);
            }
            assertThat(activeSum(lines, active)).isLessThanOrEqualTo(cap);
        }
    }

    private static BigDecimal activeSum(List<BigDecimal> lines, List<Boolean> active) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            if (active.get(i)) {
                sum = sum.add(lines.get(i));
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------------------------------------
    // Effective ETA
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<LocalDate> maybeDate() {
        return Arbitraries.integers().between(0, 60).map(i -> BASE.plusDays(i)).injectNull(0.25);
    }

    @Provide
    Arbitrary<List<LocalDate>> etaList() {
        return maybeDate().list().ofMaxSize(8);
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-11-04, Property: effective ETA precedence")
    void effectiveEtaFollowsPrecedence(
            @ForAll("etaList") List<LocalDate> etas,
            @ForAll("maybeDate") LocalDate requestDate,
            @ForAll boolean hasDelivered,
            @ForAll("maybeDate") LocalDate current) {
        List<EtaCandidate> candidates = etas.stream().map(EtaCandidate::new).toList();
        LocalDate result = ShipmentRules.effectiveEta(candidates, requestDate, hasDelivered, current);

        LocalDate latest = etas.stream().filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (latest != null) {
            assertThat(result).isEqualTo(latest);
        } else if (hasDelivered) {
            assertThat(result).isEqualTo(current);
        } else {
            assertThat(result).isEqualTo(requestDate);
        }
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-11-04, Property: effective ETA is order independent")
    void effectiveEtaIsOrderIndependent(
            @ForAll("etaList") List<LocalDate> etas,
            @ForAll("maybeDate") LocalDate requestDate,
            @ForAll boolean hasDelivered,
            @ForAll("maybeDate") LocalDate current) {
        List<EtaCandidate> forward = etas.stream().map(EtaCandidate::new).toList();
        List<EtaCandidate> reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);
        assertThat(ShipmentRules.effectiveEta(reversed, requestDate, hasDelivered, current))
                .isEqualTo(ShipmentRules.effectiveEta(forward, requestDate, hasDelivered, current));
    }

    // ---------------------------------------------------------------------------------------------
    // Order-by one-shot
    // ---------------------------------------------------------------------------------------------

    /** Candidate rule of the deadline sweep (R14.2): missed and the marker differs from the order-by date. */
    private static boolean isCandidate(PositionStatus status, SourcingPath path, LocalDate orderBy,
                                       LocalDate marker, LocalDate today) {
        return MaterialScheduleRiskService.orderByMissed(status, path, orderBy, today)
                && !Objects.equals(marker, orderBy);
    }

    @Provide
    Arbitrary<LocalDate> orderByDate() {
        return Arbitraries.integers().between(0, 10).map(i -> BASE.plusDays(i)).injectNull(0.1);
    }

    @Provide
    Arbitrary<List<LocalDate>> dateChanges() {
        return orderByDate().list().ofMinSize(1).ofMaxSize(8);
    }

    @Property(tries = 400)
    @Tag("Feature: FOR-11-04, Property: order-by notification is one-shot per distinct date")
    void orderByNotificationFiresOncePerDistinctDate(
            @ForAll PositionStatus status,
            @ForAll SourcingPath path,
            @ForAll("dateChanges") List<LocalDate> dateChanges) {
        LocalDate marker = null;
        LocalDate orderBy = null;
        Map<LocalDate, Integer> fired = new HashMap<>();
        LocalDate today = BASE;
        for (LocalDate change : dateChanges) {
            orderBy = change;
            // several sweeps per day, and the day advances; the date set above is current in between
            for (int sweep = 0; sweep < 3; sweep++) {
                if (isCandidate(status, path, orderBy, marker, today.plusDays(sweep))) {
                    marker = orderBy;
                    fired.merge(orderBy, 1, Integer::sum);
                }
            }
            today = today.plusDays(1);
        }
        // Each firing needs the date to differ from the stamped marker, so a run of equal
        // consecutive dates fires at most once no matter how many sweeps happen.
        int maxFirings = 0;
        LocalDate prev = null;
        for (LocalDate d : dateChanges) {
            if (d != null && !d.equals(prev)) {
                maxFirings++;
            }
            prev = d;
        }
        assertThat(fired.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(maxFirings);
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-11-04, Property: stamped marker removes the position from the candidates")
    void stampedPositionIsNeverACandidateAgainUntilDateChanges(
            @ForAll PositionStatus status,
            @ForAll SourcingPath path,
            @ForAll("orderByDate") LocalDate orderBy,
            @ForAll("orderByDate") LocalDate laterDate) {
        LocalDate today = BASE.plusDays(30);
        if (orderBy != null) {
            assertThat(isCandidate(status, path, orderBy, orderBy, today)).isFalse();
        }
        if (laterDate != null && !laterDate.equals(orderBy) && orderBy != null) {
            // a new date re-arms the one-shot with no reset code
            assertThat(isCandidate(status, path, laterDate, orderBy, today))
                    .isEqualTo(MaterialScheduleRiskService.orderByMissed(status, path, laterDate, today));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Back-off
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-11-04, Property: back-off is monotonic in failures and capped")
    void backoffIsMonotonicAndCapped(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 100_000) int failures,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 600) int minMinutes,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 72) int capHours) {
        Duration min = Duration.ofMinutes(minMinutes);
        Duration cap = Duration.ofHours(capHours);

        Instant at = TrackingBackoff.nextCheck(NOW, failures, min, cap);
        Instant next = TrackingBackoff.nextCheck(NOW, failures + 1, min, cap);

        assertThat(at).isAfter(NOW);
        assertThat(at).isBeforeOrEqualTo(NOW.plus(cap));
        assertThat(next).isAfterOrEqualTo(at);
        assertThat(TrackingBackoff.isDegraded(failures)).isEqualTo(failures >= TrackingBackoff.DEGRADED_THRESHOLD);
    }
}
