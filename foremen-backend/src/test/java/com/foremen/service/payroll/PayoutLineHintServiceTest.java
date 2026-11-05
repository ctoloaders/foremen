package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;

/**
 * FOR-07-06 Round 1 / bug H — {@link PayoutLineHintService}: resolves the previous calendar month
 * window, delegates to the non-advance {@link PayoutSuggestionService} suggestion, and returns only
 * the available breakdown lines. The worker check (R8.7) runs first.
 */
@ExtendWith(MockitoExtension.class)
class PayoutLineHintServiceTest {

    private static final Long WORKER = 5L;
    private static final Long MANAGER = 9L;

    @Mock
    private UserDao userDao;
    @Mock
    private PayoutSuggestionService suggestionService;

    private PayoutLineHintService service;

    @BeforeEach
    void setUp() {
        service = new PayoutLineHintService(userDao, suggestionService);
        // Today = 2026-03-15 ⇒ previous month is February 2026.
        service.setClock(Clock.fixed(LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC));
        lenient().when(userDao.findById(WORKER)).thenReturn(Optional.of(worker(WORKER, "WORKER")));
        lenient().when(userDao.findById(MANAGER)).thenReturn(Optional.of(worker(MANAGER, "MANAGER")));
    }

    @Test
    @DisplayName("null month ⇒ previous calendar month window, available lines only (bug H)")
    void previousMonthDefaultsToClock() {
        PayoutLineDto available = line(101L, true);
        PayoutLineDto unavailable = line(102L, false);
        when(suggestionService.suggest(eq(WORKER), eq(LocalDate.of(2026, 2, 1)),
                eq(LocalDate.of(2026, 2, 28)), eq(false)))
                .thenReturn(suggestion(List.of(available, unavailable)));

        List<PayoutLineDto> hints = service.previousMonthHints(WORKER, null);

        assertThat(hints).extracting(PayoutLineDto::workReportRoomId).containsExactly(101L);
    }

    @Test
    @DisplayName("explicit month ⇒ the month before that month's window")
    void explicitMonthResolvesPrevious() {
        when(suggestionService.suggest(eq(WORKER), eq(LocalDate.of(2026, 6, 1)),
                eq(LocalDate.of(2026, 6, 30)), eq(false)))
                .thenReturn(suggestion(List.of(line(201L, true))));

        List<PayoutLineDto> hints = service.previousMonthHints(WORKER, LocalDate.of(2026, 7, 20));

        assertThat(hints).extracting(PayoutLineDto::workReportRoomId).containsExactly(201L);
        verify(suggestionService).suggest(WORKER, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), false);
    }

    @Test
    @DisplayName("non-WORKER ⇒ 422 before any suggestion is requested (R8.7)")
    void rejectsNonWorker() {
        assertThatThrownBy(() -> service.previousMonthHints(MANAGER, null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage(PayrollWriteSupport.WORKER_INVALID);
        verifyNoInteractions(suggestionService);
    }

    private static PayoutSuggestionDto suggestion(List<PayoutLineDto> breakdown) {
        return new PayoutSuggestionDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, breakdown);
    }

    private static PayoutLineDto line(Long id, boolean available) {
        return new PayoutLineDto(id, 1L, "Project", "Work", "Room",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 10), "BASE", false, BigDecimal.ZERO,
                new BigDecimal("1.000"), available ? new BigDecimal("40.00") : null,
                available ? new BigDecimal("40.00") : null, BigDecimal.ZERO,
                available ? new BigDecimal("40.00") : null, BigDecimal.ZERO, available);
    }

    private static UserEntity worker(Long id, String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setRole(role);
        return u;
    }
}
