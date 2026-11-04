package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;

/**
 * FOR-07-04 task 6.8 — the pure parts of the candidate providers and the per-line pool narrowing.
 *
 * <p>Validates: Requirements 14.4, 14.5, 14.6
 */
@Tag("Feature: FOR-07-04-bank-ledger, task 6.8: candidate providers")
class DetectionCandidateProvidersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Test
    @DisplayName("R14.4: open tranche remainders are amountGross − paidGross, settled tranches dropped")
    void openRemainders() {
        PaymentScheduleEntity s = new PaymentScheduleEntity();
        s.getTranches().add(tranche("1000.00", "0.00"));
        s.getTranches().add(tranche("500.00", "500.00"));
        s.getTranches().add(tranche("300.00", "100.50"));

        assertThat(DetectionCandidateProviders.openRemainders(s))
                .extracting(BigDecimal::toPlainString).containsExactly("1000.00", "199.50");
    }

    @Test
    @DisplayName("R14.6: a document's payment status is derived from Σ links")
    void documentPaymentStatus() {
        ProcurementDocumentEntity d = new ProcurementDocumentEntity();
        d.setId(5L);
        d.setDocumentTotal(new BigDecimal("200.00"));
        d.setUploadedAt(OffsetDateTime.of(2026, 3, 11, 23, 30, 0, 0, ZoneOffset.UTC));

        assertThat(DetectionCandidateProviders.toDocumentCandidate(d, null).paymentStatus())
                .isEqualTo(DocumentPaymentStatus.UNMATCHED);
        assertThat(DetectionCandidateProviders.toDocumentCandidate(d, new BigDecimal("50.00")).paymentStatus())
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        assertThat(DetectionCandidateProviders.toDocumentCandidate(d, new BigDecimal("200.00")).paymentStatus())
                .isEqualTo(DocumentPaymentStatus.PAID);
        assertThat(DetectionCandidateProviders.toDocumentCandidate(d, null).uploadDate())
                .isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    @DisplayName("City is taken from the postal code part of a Polish address")
    void city() {
        assertThat(DetectionCandidateProviders.city("ul. Długa 1, 30-001 Kraków, Polska")).isEqualTo("Kraków");
        assertThat(DetectionCandidateProviders.city("no postal code")).isNull();
        assertThat(DetectionCandidateProviders.city(null)).isNull();
    }

    @Test
    @DisplayName("Pool narrowing: INCOME sees schedules only; EXPENSE sees in-window, non-consumed payouts / documents")
    void poolNarrowing() {
        ScheduleCandidate schedule = new ScheduleCandidate(1L, 2L, true, "Klient", null,
                List.of(new BigDecimal("100.00")));
        PayoutCandidate inWindow = payout(11, DAY.plusDays(3));
        PayoutCandidate outOfWindow = payout(12, DAY.minusDays(CandidatePool.PAYOUT_WINDOW_DAYS + 1));
        PayoutCandidate consumed = payout(13, DAY);
        DocumentCandidate docIn = document(21, DAY.minusDays(CandidatePool.DOC_DAYS_BEFORE));
        DocumentCandidate docOut = document(22, DAY.plusDays(CandidatePool.DOC_DAYS_AFTER + 1));
        CandidatePool pool = new CandidatePool(null, List.of(schedule), List.of(inWindow, outOfWindow, consumed),
                List.of(docIn, docOut), null);

        MatchInputs income = pool.inputsFor(facts(TxnFacts.Direction.INCOME), List.of(), Set.of());
        assertThat(income.schedules()).containsExactly(schedule);
        assertThat(income.payouts()).isEmpty();
        assertThat(income.documents()).isEmpty();

        MatchInputs expense = pool.inputsFor(facts(TxnFacts.Direction.EXPENSE), List.of(),
                Set.of(CandidatePool.labelKey(TargetType.WORKER_PAYOUT, 13L)));
        assertThat(expense.schedules()).isEmpty();
        assertThat(expense.payouts()).containsExactly(inWindow);
        assertThat(expense.documents()).containsExactly(docIn);
    }

    private static TxnFacts facts(TxnFacts.Direction direction) {
        BigDecimal amount = direction == TxnFacts.Direction.INCOME ? new BigDecimal("100.00")
                : new BigDecimal("-100.00");
        return new TxnFacts(direction, amount, DAY, "t", "c", null, null);
    }

    private static PaymentTrancheEntity tranche(String gross, String paid) {
        PaymentTrancheEntity t = new PaymentTrancheEntity();
        t.setAmountGross(new BigDecimal(gross));
        t.setPaidGross(new BigDecimal(paid));
        return t;
    }

    private static PayoutCandidate payout(long id, LocalDate date) {
        return new PayoutCandidate(id, 1L, "W", new BigDecimal("100.00"), date, PayoutStatus.PLANNED,
                PaymentChannel.BANK_TRANSFER, false);
    }

    private static DocumentCandidate document(long id, LocalDate invoiceDate) {
        return new DocumentCandidate(id, new BigDecimal("100.00"), null, "S", null, invoiceDate, invoiceDate,
                DocumentPaymentStatus.UNMATCHED);
    }
}
