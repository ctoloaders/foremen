package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.exception.ForemenApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** <b>Validates: Requirements 6.4, 13.2, 14.1</b> */
class ApprovalDeadlinePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Test
    void nullAndTodayAndFutureAreValid() {
        assertThatCode(() -> ApprovalDeadlinePolicy.validate(null, TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> ApprovalDeadlinePolicy.validate(TODAY, TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> ApprovalDeadlinePolicy.validate(TODAY.plusDays(1), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void pastDeadlineIsRejectedWith400() {
        assertThatThrownBy(() -> ApprovalDeadlinePolicy.validate(TODAY.minusDays(1), TODAY))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.positionApproval.deadline.invalid");
    }

    @Test
    void overdueOnlyForOpenRoundBeforeToday() {
        assertThat(ApprovalDeadlinePolicy.isOverdue(ApprovalRoundStatus.OPEN, TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(ApprovalDeadlinePolicy.isOverdue(ApprovalRoundStatus.OPEN, TODAY, TODAY)).isFalse();
        assertThat(ApprovalDeadlinePolicy.isOverdue(ApprovalRoundStatus.OPEN, null, TODAY)).isFalse();
        assertThat(ApprovalDeadlinePolicy.isOverdue(ApprovalRoundStatus.ACCEPTED, TODAY.minusDays(5), TODAY)).isFalse();
    }

    @Test
    void todayRollsOverAtWarsawMidnight() {
        // Winter (UTC+1): 22:59:59Z is still 23:59:59 on 9 Jan, 23:00:00Z is 00:00:00 on 10 Jan.
        Clock before = Clock.fixed(Instant.parse("2026-01-09T22:59:59Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(Instant.parse("2026-01-09T23:00:00Z"), ZoneOffset.UTC);
        assertThat(ApprovalDeadlinePolicy.today(before)).isEqualTo(LocalDate.of(2026, 1, 9));
        assertThat(ApprovalDeadlinePolicy.today(after)).isEqualTo(LocalDate.of(2026, 1, 10));
        // Summer (UTC+2)
        Clock summer = Clock.fixed(Instant.parse("2026-07-09T22:00:00Z"), ZoneOffset.UTC);
        assertThat(ApprovalDeadlinePolicy.today(summer)).isEqualTo(LocalDate.of(2026, 7, 10));
    }
}
