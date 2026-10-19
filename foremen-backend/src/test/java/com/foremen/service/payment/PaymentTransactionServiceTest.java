package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;
import com.foremen.service.payment.provider.IncomingBankTxn;
import com.foremen.service.payment.provider.OnlineWebhookEvent;

import jakarta.persistence.EntityManager;

/**
 * Plain Mockito unit tests for {@link PaymentTransactionService} (FOR-06-07 task 8.1), covering the
 * three channel entry points in isolation with mocked DAOs, a mocked {@link ReconciliationService},
 * a mocked {@link ApplicationEventPublisher}, and a mocked {@link EntityManager}. The
 * {@link PaymentDtoMapper} is not exercised here (the asserted paths return {@code void} or are
 * reject paths), so it is mocked too.
 *
 * <p>Covered:
 * <ul>
 *   <li><b>online webhook mapping (R7.3)</b> — a {@code CONFIRMED} event stamps + confirms + runs
 *       reconciliation; a {@code FAILED} event marks {@code FAILED} and publishes an
 *       {@link PaymentTransactionService.OnlineFailedEvent} without reconciling; a {@code REFUNDED}
 *       reversal marks {@code REFUNDED} and publishes the same failure event; an unknown
 *       {@code externalRef} is 404;</li>
 *   <li><b>uncorrelated bank line → unmatched (R8.4)</b> — an un-correlatable line is recorded with a
 *       {@code null} schedule (unmatched) and is never reconciled, and ingest does not fail;</li>
 *   <li><b>{@code feeGross} recorded, not deducted (R7.4)</b> — the confirmed webhook stores the
 *       reported {@code feeGross} on the transaction and the full {@code amountGross} (not
 *       {@code amount − fee}) is what reconciliation receives.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 7.3, 7.4, 8.4</b>
 */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    private static final Long SCHEDULE_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final String ONLINE_REF = "p24-ref-123";

    @Mock
    private PaymentTransactionDao paymentTransactionDao;
    @Mock
    private PaymentScheduleDao paymentScheduleDao;
    @Mock
    private UserDao userDao;
    @Mock
    private ReconciliationService reconciliationService;
    @Mock
    private PaymentDtoMapper paymentDtoMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private EntityManager entityManager;

    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentTransactionService(
                paymentTransactionDao, paymentScheduleDao, userDao, reconciliationService,
                paymentDtoMapper, eventPublisher, entityManager);
        lenient().when(paymentTransactionDao.save(any(PaymentTransactionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentScheduleEntity schedule() {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        schedule.setProject(project);
        return schedule;
    }

    private PaymentTransactionEntity onlineTxn(TransactionStatus status) {
        PaymentTransactionEntity txn = new PaymentTransactionEntity();
        txn.setId(500L);
        txn.setSchedule(schedule());
        txn.setChannel(PaymentChannel.ONLINE);
        txn.setExternalRef(ONLINE_REF);
        txn.setAmountGross(new BigDecimal("1000.00"));
        txn.setFeeGross(BigDecimal.ZERO);
        txn.setStatus(status);
        return txn;
    }

    // --- online webhook mapping (R7.3) ---

    @Test
    @DisplayName("webhook CONFIRMED stamps amount/fee, marks CONFIRMED and reconciles (R7.3)")
    void webhookConfirmedConfirmsAndReconciles() {
        PaymentTransactionEntity pending = onlineTxn(TransactionStatus.PENDING);
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of(pending));

        OnlineWebhookEvent event = new OnlineWebhookEvent(
                ONLINE_REF, TransactionStatus.CONFIRMED, new BigDecimal("1000.00"), new BigDecimal("25.00"));
        service.onOnlineWebhook(event);

        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
        verify(reconciliationService).reconcile(pending);
    }

    @Test
    @DisplayName("webhook FAILED marks FAILED, publishes OnlineFailedEvent, does not reconcile (R7.3)")
    void webhookFailedMarksFailedAndPublishes() {
        PaymentTransactionEntity pending = onlineTxn(TransactionStatus.PENDING);
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of(pending));

        service.onOnlineWebhook(new OnlineWebhookEvent(ONLINE_REF, TransactionStatus.FAILED, null, null));

        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verify(reconciliationService, never()).reconcile(any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PaymentTransactionService.OnlineFailedEvent.class);
        PaymentTransactionService.OnlineFailedEvent evt =
                (PaymentTransactionService.OnlineFailedEvent) captor.getValue();
        assertThat(evt.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(evt.scheduleId()).isEqualTo(SCHEDULE_ID);
    }

    @Test
    @DisplayName("webhook REFUNDED (reversal) marks REFUNDED and publishes OnlineFailedEvent (R7.3)")
    void webhookRefundedMarksRefundedAndPublishes() {
        PaymentTransactionEntity confirmed = onlineTxn(TransactionStatus.CONFIRMED);
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of(confirmed));

        service.onOnlineWebhook(new OnlineWebhookEvent(ONLINE_REF, TransactionStatus.REFUNDED, null, null));

        assertThat(confirmed.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        verify(reconciliationService, never()).reconcile(any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PaymentTransactionService.OnlineFailedEvent evt =
                (PaymentTransactionService.OnlineFailedEvent) captor.getValue();
        assertThat(evt.status()).isEqualTo(TransactionStatus.REFUNDED);
    }

    @Test
    @DisplayName("webhook for an unknown externalRef is rejected 404 (R7.3)")
    void webhookUnknownExternalRefRejected() {
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.onOnlineWebhook(
                new OnlineWebhookEvent("missing", TransactionStatus.CONFIRMED, null, null)))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("webhook CONFIRMED on an already-CONFIRMED online txn is a no-op (idempotent, R5.8)")
    void webhookConfirmedIdempotent() {
        PaymentTransactionEntity confirmed = onlineTxn(TransactionStatus.CONFIRMED);
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of(confirmed));

        service.onOnlineWebhook(new OnlineWebhookEvent(
                ONLINE_REF, TransactionStatus.CONFIRMED, new BigDecimal("1000.00"), new BigDecimal("25.00")));

        verify(reconciliationService, never()).reconcile(any());
    }

    // --- feeGross recorded, not deducted (R7.4) ---

    @Test
    @DisplayName("webhook CONFIRMED records feeGross and reconciles the full amountGross, not amount - fee (R7.4)")
    void feeRecordedNotDeductedFromAllocatedAmount() {
        PaymentTransactionEntity pending = onlineTxn(TransactionStatus.PENDING);
        when(paymentTransactionDao.findAll(any(Specification.class))).thenReturn(List.of(pending));

        service.onOnlineWebhook(new OnlineWebhookEvent(
                ONLINE_REF, TransactionStatus.CONFIRMED, new BigDecimal("1000.00"), new BigDecimal("25.00")));

        // R7.4: the fee is recorded on the transaction...
        assertThat(pending.getFeeGross()).isEqualByComparingTo("25.00");
        // ...and the FULL gross amount (not amount - fee = 975.00) is what reconciliation receives.
        ArgumentCaptor<PaymentTransactionEntity> captor =
                ArgumentCaptor.forClass(PaymentTransactionEntity.class);
        verify(reconciliationService).reconcile(captor.capture());
        assertThat(captor.getValue().getAmountGross()).isEqualByComparingTo("1000.00");
    }

    // --- uncorrelated bank line -> unmatched (R8.4) ---

    @Test
    @DisplayName("an uncorrelated bank line is recorded unmatched (null schedule), never reconciled, ingest does not fail (R8.4)")
    void uncorrelatedBankLineRecordedUnmatched() {
        // A blank correlation key cannot be resolved -> unmatched.
        when(paymentTransactionDao.findByScheduleIsNull()).thenReturn(List.of());

        IncomingBankTxn txn = new IncomingBankTxn(
                new BigDecimal("500.00"), "PLN", "bank-ref-1", Instant.now(), null);
        service.ingestBankTransaction(txn);

        ArgumentCaptor<PaymentTransactionEntity> captor =
                ArgumentCaptor.forClass(PaymentTransactionEntity.class);
        verify(paymentTransactionDao).save(captor.capture());
        PaymentTransactionEntity saved = captor.getValue();
        assertThat(saved.getSchedule()).isNull();                 // R8.4: unmatched.
        assertThat(saved.getChannel()).isEqualTo(PaymentChannel.BANK_TRANSFER);
        assertThat(saved.getExternalRef()).isEqualTo("bank-ref-1");
        verify(reconciliationService, never()).reconcile(any());  // not allocated.
    }

    @Test
    @DisplayName("a correlated bank line is recorded CONFIRMED against its schedule and reconciled (R8.3)")
    void correlatedBankLineRecordedConfirmedAndReconciled() {
        when(paymentScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(schedule()));
        when(paymentTransactionDao.findByScheduleIdAndChannelAndExternalRef(
                eq(SCHEDULE_ID), eq(PaymentChannel.BANK_TRANSFER), eq("bank-ref-2")))
                .thenReturn(Optional.empty());

        IncomingBankTxn txn = new IncomingBankTxn(
                new BigDecimal("500.00"), "PLN", "bank-ref-2", Instant.now(), String.valueOf(PROJECT_ID));
        service.ingestBankTransaction(txn);

        ArgumentCaptor<PaymentTransactionEntity> captor =
                ArgumentCaptor.forClass(PaymentTransactionEntity.class);
        verify(paymentTransactionDao).save(captor.capture());
        PaymentTransactionEntity saved = captor.getValue();
        assertThat(saved.getSchedule()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
        verify(reconciliationService).reconcile(saved);
    }

    @Test
    @DisplayName("a re-delivered correlated bank line with an already-recorded externalRef is a no-op (idempotent, R5.8)")
    void correlatedBankLineIdempotent() {
        when(paymentScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(schedule()));
        when(paymentTransactionDao.findByScheduleIdAndChannelAndExternalRef(
                eq(SCHEDULE_ID), eq(PaymentChannel.BANK_TRANSFER), eq("dup-ref")))
                .thenReturn(Optional.of(new PaymentTransactionEntity()));

        IncomingBankTxn txn = new IncomingBankTxn(
                new BigDecimal("500.00"), "PLN", "dup-ref", Instant.now(), String.valueOf(PROJECT_ID));
        service.ingestBankTransaction(txn);

        verify(paymentTransactionDao, never()).save(any());
        verify(reconciliationService, never()).reconcile(any());
    }
}
