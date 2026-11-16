package com.foremen.service.clientpayment;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.clientpayment.ClientPaymentStatusMapper.StoredMatch;
import com.foremen.service.payment.TrancheStatus;
import com.foremen.service.procurement.proforma.ProformaStatus;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Property tests of {@link ClientPaymentStatusMapper}.
 *
 * <p><b>Validates: Requirements 1.9</b>
 */
class ClientPaymentStatusMapperPropertyTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 1);

    private static List<String> statuses(ClientPaymentKind kind) {
        return kind == ClientPaymentKind.WORKS
                ? Arrays.stream(TrancheStatus.values()).map(Enum::name).toList()
                : Arrays.stream(ProformaStatus.values()).map(Enum::name).toList();
    }

    record Input(ClientPaymentKind kind, String stored, LocalDate dueDate, LocalDate today) {
    }

    @Provide
    Arbitrary<Input> inputs() {
        Arbitrary<LocalDate> dates = Arbitraries.integers().between(-60, 60).map(BASE::plusDays);
        Arbitrary<LocalDate> dueDates = dates.injectNull(0.1);
        return Arbitraries.of(ClientPaymentKind.values()).flatMap(kind ->
                Combinators.combine(Arbitraries.of(statuses(kind)), dueDates, dates)
                        .as((s, d, t) -> new Input(kind, s, d, t)));
    }

    /** Total and deterministic: every valid pair maps to a status, always the same one. */
    @Property
    void totalAndDeterministic(@ForAll("inputs") Input in) {
        DisplayStatus a = ClientPaymentStatusMapper.toDisplay(in.kind(), in.stored(), in.dueDate(), in.today());
        DisplayStatus b = ClientPaymentStatusMapper.toDisplay(in.kind(), in.stored(), in.dueDate(), in.today());
        assertThat(a).isNotNull().isEqualTo(b);
    }

    /** The inverse of the mapped display status has exactly one accepting match; no other status accepts. */
    @Property
    void inverseRoundTrip(@ForAll("inputs") Input in) {
        DisplayStatus display = ClientPaymentStatusMapper.toDisplay(in.kind(), in.stored(), in.dueDate(),
                in.today());
        long accepting = ClientPaymentStatusMapper.fromDisplay(in.kind(), display).stream()
                .filter(m -> m.accepts(in.stored(), in.dueDate(), in.today())).count();
        assertThat(accepting).isEqualTo(1);

        for (DisplayStatus other : DisplayStatus.values()) {
            if (other == display) {
                continue;
            }
            assertThat(ClientPaymentStatusMapper.fromDisplay(in.kind(), other).stream()
                    .anyMatch(m -> m.accepts(in.stored(), in.dueDate(), in.today()))).isFalse();
        }
    }

    /** Whatever a match accepts maps back to the display status it was listed under. */
    @Property
    void inverseMatchesMapBack(@ForAll("inputs") Input in, @ForAll DisplayStatus display) {
        for (StoredMatch m : ClientPaymentStatusMapper.fromDisplay(in.kind(), display)) {
            if (m.accepts(in.stored(), in.dueDate(), in.today())) {
                assertThat(ClientPaymentStatusMapper.toDisplay(in.kind(), in.stored(), in.dueDate(), in.today()))
                        .isEqualTo(display);
            }
        }
    }

    /** Display OVERDUE implies the overdue flag; paid and cancelled are never overdue; no date, no overdue. */
    @Property
    void overdueFlagConsistent(@ForAll("inputs") Input in) {
        boolean overdue = ClientPaymentStatusMapper.overdue(in.kind(), in.stored(), in.dueDate(), in.today());
        DisplayStatus display = ClientPaymentStatusMapper.toDisplay(in.kind(), in.stored(), in.dueDate(),
                in.today());
        if (display == DisplayStatus.OVERDUE) {
            assertThat(overdue).isTrue();
        }
        if (display == DisplayStatus.PAID || display == DisplayStatus.CANCELLED) {
            assertThat(overdue).isFalse();
        }
        if (in.dueDate() == null && !"OVERDUE".equals(in.stored())) {
            assertThat(overdue).isFalse();
        }
    }

    @Test
    void tableExamples() {
        LocalDate today = BASE;
        assertThat(ClientPaymentStatusMapper.toDisplay(ClientPaymentKind.WORKS, "PENDING", today, today))
                .isEqualTo(DisplayStatus.PLANNED);
        assertThat(ClientPaymentStatusMapper.toDisplay(ClientPaymentKind.MATERIALS, "ISSUED",
                today.minusDays(1), today)).isEqualTo(DisplayStatus.OVERDUE);
        assertThat(ClientPaymentStatusMapper.toDisplay(ClientPaymentKind.MATERIALS, "ISSUED", today, today))
                .isEqualTo(DisplayStatus.ISSUED);
        assertThat(ClientPaymentStatusMapper.toDisplay(ClientPaymentKind.MATERIALS, "PARTIALLY_PAID",
                today.minusDays(1), today)).isEqualTo(DisplayStatus.OVERDUE);
        assertThat(ClientPaymentStatusMapper.toDisplay(ClientPaymentKind.MATERIALS, "PAID",
                today.minusDays(9), today)).isEqualTo(DisplayStatus.PAID);
        assertThat(ClientPaymentStatusMapper.fromDisplay(ClientPaymentKind.WORKS, DisplayStatus.DRAFT)).isEmpty();
    }
}
