package com.foremen.service.procurement.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WorksStartResolverTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 3, 2);

    @Test
    void halfDayOneAndTwoStartOnAnchor() {
        assertThat(WorksStartResolver.startDate(ANCHOR, 1)).isEqualTo(ANCHOR);
        assertThat(WorksStartResolver.startDate(ANCHOR, 2)).isEqualTo(ANCHOR);
    }

    @Test
    void halfDayThreeAndFourStartNextDay() {
        assertThat(WorksStartResolver.startDate(ANCHOR, 3)).isEqualTo(ANCHOR.plusDays(1));
        assertThat(WorksStartResolver.startDate(ANCHOR, 4)).isEqualTo(ANCHOR.plusDays(1));
        assertThat(WorksStartResolver.startDate(ANCHOR, 21)).isEqualTo(ANCHOR.plusDays(10));
    }

    @Test
    void missingAnchorBarOrInvalidIndexGivesNull() {
        assertThat(WorksStartResolver.startDate(null, 3)).isNull();
        assertThat(WorksStartResolver.startDate(ANCHOR, null)).isNull();
        assertThat(WorksStartResolver.startDate(ANCHOR, 0)).isNull();
    }
}
