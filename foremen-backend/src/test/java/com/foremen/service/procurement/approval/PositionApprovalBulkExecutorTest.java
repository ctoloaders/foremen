package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import org.mockito.ArgumentCaptor;

import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.approval.PositionApprovalBulkExecutor.AcceptItem;
import com.foremen.service.procurement.approval.PositionApprovalBulkExecutor.Result;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;

/**
 * FOR-11-06 task 6.4 (Requirements 8.4, 8.5, 8.6): per-item isolation, the 200-item cap and the failure keys of
 * {@link PositionApprovalBulkExecutor}. Pure Mockito, no Spring context.
 */
class PositionApprovalBulkExecutorTest {

    private PositionApprovalService approvalService;
    private PositionApprovalRoundDao roundDao;
    private PlatformTransactionManager txManager;
    private PositionApprovalBulkExecutor executor;
    private final Decider decider = Decider.staffOnBehalf(7L);

    @BeforeEach
    void setUp() {
        approvalService = mock(PositionApprovalService.class);
        roundDao = mock(PositionApprovalRoundDao.class);
        txManager = mock(PlatformTransactionManager.class);
        executor = new PositionApprovalBulkExecutor(approvalService, roundDao, txManager);
    }

    private PositionApprovalRoundEntity round(Long id, Long positionId) {
        PositionApprovalRoundEntity r = mock(PositionApprovalRoundEntity.class);
        PurchasePositionEntity p = mock(PurchasePositionEntity.class);
        when(r.getId()).thenReturn(id);
        when(r.getPosition()).thenReturn(p);
        when(p.getId()).thenReturn(positionId);
        return r;
    }

    private AcceptItem item(Long positionId) {
        return new AcceptItem(positionId, null, new BigDecimal("10.00"), null);
    }

    @Test
    void acceptOnBehalf_partialFailureDoesNotAffectOtherItems() {
        PositionApprovalRoundEntity r101_1 = round(101L, 1L);
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r101_1));
        PositionApprovalRoundEntity r102_2 = round(102L, 2L);
        when(roundDao.findOpenByPositionId(2L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r102_2));
        when(roundDao.findOpenByPositionId(3L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.empty());
        PositionApprovalRoundEntity r104_4 = round(104L, 4L);
        when(roundDao.findOpenByPositionId(4L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r104_4));
        doThrow(new ForemenApiException(HttpStatus.CONFLICT, PositionApprovalService.ERROR_PRICE_CHANGED))
                .when(approvalService).accept(eq(102L), any(AcceptCommand.class), eq(decider));

        Result result = executor.acceptOnBehalf(List.of(item(1L), item(2L), item(3L), item(4L)), decider);

        assertThat(result.succeeded()).containsExactly(1L, 4L);
        assertThat(result.failed()).extracting(f -> f.positionId() + ":" + f.errorKey()).containsExactly(
                "2:" + PositionApprovalService.ERROR_PRICE_CHANGED,
                "3:" + PositionApprovalService.ERROR_ROUND_NOT_FOUND);
        verify(approvalService).accept(eq(101L), any(AcceptCommand.class), eq(decider));
        verify(approvalService).accept(eq(104L), any(AcceptCommand.class), eq(decider));
    }

    @Test
    void acceptOnBehalf_passesCommandFieldsToTheService() {
        PositionApprovalRoundEntity r101_1 = round(101L, 1L);
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r101_1));

        executor.acceptOnBehalf(List.of(new AcceptItem(1L, 5L, new BigDecimal("12.50"), "ok")), decider);

        ArgumentCaptor<AcceptCommand> cmd = ArgumentCaptor.forClass(AcceptCommand.class);
        verify(approvalService).accept(eq(101L), cmd.capture(), eq(decider));
        assertThat(cmd.getValue().optionId()).isEqualTo(5L);
        assertThat(cmd.getValue().expectedUnitSalePrice()).isEqualByComparingTo("12.50");
        assertThat(cmd.getValue().comment()).isEqualTo("ok");
    }

    @Test
    void acceptOnBehalf_unexpectedExceptionBecomesInternalFailureAndContinues() {
        PositionApprovalRoundEntity r101_1 = round(101L, 1L);
        when(roundDao.findOpenByPositionId(1L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r101_1));
        PositionApprovalRoundEntity r102_2 = round(102L, 2L);
        when(roundDao.findOpenByPositionId(2L, ApprovalRoundStatus.OPEN)).thenReturn(Optional.of(r102_2));
        doThrow(new IllegalStateException("boom")).when(approvalService)
                .accept(eq(101L), any(AcceptCommand.class), eq(decider));

        Result result = executor.acceptOnBehalf(List.of(item(1L), item(2L)), decider);

        assertThat(result.succeeded()).containsExactly(2L);
        assertThat(result.failed()).extracting(f -> f.positionId() + ":" + f.errorKey())
                .containsExactly("1:error.internal");
    }

    @Test
    void eachItemRunsInItsOwnRequiresNewTransaction() {
        java.util.Map<Long, PositionApprovalRoundEntity> rounds = new java.util.HashMap<>();
        for (long pid = 1; pid <= 3; pid++) {
            rounds.put(pid, round(100L + pid, pid));
        }
        when(roundDao.findOpenByPositionId(any(), eq(ApprovalRoundStatus.OPEN))).thenAnswer(inv -> {
            Long pid = inv.getArgument(0);
            return Optional.of(rounds.get(pid));
        });

        executor.acceptOnBehalf(List.of(item(1L), item(2L), item(3L)), decider);

        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, org.mockito.Mockito.times(3)).getTransaction(def.capture());
        assertThat(def.getAllValues()).allSatisfy(d ->
                assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void withdraw_partialFailureKeyedByPositionId() {
        PositionApprovalRoundEntity r11_1 = round(11L, 1L);
        when(roundDao.findById(11L)).thenReturn(Optional.of(r11_1));
        PositionApprovalRoundEntity r12_2 = round(12L, 2L);
        when(roundDao.findById(12L)).thenReturn(Optional.of(r12_2));
        when(roundDao.findById(13L)).thenReturn(Optional.empty());
        doThrow(new ForemenApiException(HttpStatus.CONFLICT, PositionApprovalService.ERROR_ROUND_NOT_OPEN))
                .when(approvalService).withdraw(12L);
        doThrow(new ForemenApiException(HttpStatus.NOT_FOUND, PositionApprovalService.ERROR_ROUND_NOT_FOUND))
                .when(approvalService).withdraw(13L);

        Result result = executor.withdraw(List.of(11L, 12L, 13L));

        assertThat(result.succeeded()).containsExactly(1L);
        assertThat(result.failed()).extracting(f -> f.positionId() + ":" + f.errorKey()).containsExactly(
                "2:" + PositionApprovalService.ERROR_ROUND_NOT_OPEN,
                "null:" + PositionApprovalService.ERROR_ROUND_NOT_FOUND);
    }

    @Test
    void withdraw_unexpectedExceptionBecomesInternalFailure() {
        PositionApprovalRoundEntity r11_1 = round(11L, 1L);
        when(roundDao.findById(11L)).thenReturn(Optional.of(r11_1));
        doThrow(new IllegalStateException("boom")).when(approvalService).withdraw(11L);

        Result result = executor.withdraw(List.of(11L));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).extracting(f -> f.positionId() + ":" + f.errorKey())
                .containsExactly("1:error.internal");
    }

    @Test
    void capAllowsExactlyTwoHundredItemsAndRejectsMore() {
        PositionApprovalBulkExecutor.assertWithinCap(ids(PositionApprovalBulkExecutor.MAX_ITEMS));
        PositionApprovalBulkExecutor.assertWithinCap(null);
        PositionApprovalBulkExecutor.assertWithinCap(Collections.emptyList());

        assertThatThrownBy(() -> PositionApprovalBulkExecutor.assertWithinCap(
                ids(PositionApprovalBulkExecutor.MAX_ITEMS + 1)))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessageCode()).isEqualTo(PositionApprovalBulkExecutor.ERROR_TOO_MANY);
                });
        assertThat(PositionApprovalBulkExecutor.MAX_ITEMS).isEqualTo(200);
        verify(approvalService, never()).accept(any(), any(), any());
    }

    private static List<Long> ids(int n) {
        return LongStream.rangeClosed(1, n).boxed().collect(Collectors.toList());
    }
}
