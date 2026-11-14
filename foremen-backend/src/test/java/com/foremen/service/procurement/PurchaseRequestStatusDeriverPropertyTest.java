package com.foremen.service.procurement;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.RequestStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** **Validates: Requirements 11.8, 11.9** */
class PurchaseRequestStatusDeriverPropertyTest {

    private static final Set<PositionStatus> RELEASED_OR_LATER = EnumSet.of(
            PositionStatus.WITH_BUYER, PositionStatus.ORDERED, PositionStatus.SUPPLIER_PAID,
            PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED);

    @Provide
    Arbitrary<List<PositionStatus>> positions() {
        return Arbitraries.of(PositionStatus.class).list().ofMaxSize(8);
    }

    @Provide
    Arbitrary<Integer> days() {
        return Arbitraries.integers().between(-400, 400);
    }

    @Property
    void matchesRulesAndIgnoresOrder(@ForAll("positions") List<PositionStatus> ps) {
        List<PositionStatus> active = ps.stream().filter(s -> s != PositionStatus.CANCELLED).toList();
        RequestStatus expected;
        if (ps.isEmpty()) expected = RequestStatus.OPEN;
        else if (active.isEmpty()) expected = RequestStatus.CANCELLED;
        else if (active.stream().allMatch(s -> s == PositionStatus.RECEIVED)) expected = RequestStatus.COMPLETED;
        else if (active.stream().allMatch(RELEASED_OR_LATER::contains)) expected = RequestStatus.RELEASED;
        else expected = RequestStatus.OPEN;
        assertThat(PurchaseRequestStatusDeriver.derive(ps)).isEqualTo(expected);

        List<PositionStatus> reversed = new ArrayList<>(ps);
        Collections.reverse(reversed);
        assertThat(PurchaseRequestStatusDeriver.derive(reversed)).isEqualTo(expected);
    }

    @Property
    void overdueRule(@ForAll("days") int offsetDays, @ForAll RequestStatus status) {
        Clock clock = Clock.fixed(Instant.parse("2025-03-30T23:30:00Z"), ZoneOffset.UTC);
        PurchaseRequestOverdue o = new PurchaseRequestOverdue(clock);
        LocalDate today = LocalDate.of(2025, 3, 31); // Warsaw date (UTC+2 after DST switch)
        assertThat(o.today()).isEqualTo(today);
        boolean expected = offsetDays < 0
                && status != RequestStatus.COMPLETED && status != RequestStatus.CANCELLED;
        assertThat(o.isOverdue(today.plusDays(offsetDays), status)).isEqualTo(expected);
    }
}
