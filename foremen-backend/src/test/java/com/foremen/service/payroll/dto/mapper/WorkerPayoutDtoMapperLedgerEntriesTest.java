package com.foremen.service.payroll.dto.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.payroll.dto.PayoutLedgerEntryDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

/**
 * FOR-07-04 task 6.6 — the payout read model's {@code ledgerEntries} (Requirement 16.3) is filled
 * through the {@code PayoutLedgerEntriesProvider} seam: empty while no provider bean exists, the
 * provider's lines once the bank ledger contributes one.
 */
class WorkerPayoutDtoMapperLedgerEntriesTest {

    @Test
    @DisplayName("without a provider ledgerEntries is an empty list (never null)")
    void emptyWithoutProvider() {
        WorkerPayoutDto dto = new WorkerPayoutDtoMapperImpl().toDto(payout(42L));

        assertThat(dto.ledgerEntries()).isNotNull().isEmpty();
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.workerName()).isEqualTo("Jan");
    }

    @Test
    @DisplayName("a provider's linked lines are exposed for the payout id")
    void providerLinesExposed() {
        PayoutLedgerEntryDto line = new PayoutLedgerEntryDto(7L, "BANK", LocalDate.of(2026, 5, 14),
                "mBank PLN", new BigDecimal("100.00"));
        WorkerPayoutDtoMapperImpl mapper = new WorkerPayoutDtoMapperImpl();
        mapper.setLedgerEntriesProvider(id -> id == 42L ? List.of(line) : List.of());

        assertThat(mapper.toDto(payout(42L)).ledgerEntries()).containsExactly(line);
        assertThat(mapper.toDto(payout(43L)).ledgerEntries()).isEmpty();
    }

    @Test
    @DisplayName("a null provider result and a null provider fall back to empty")
    void nullSafe() {
        WorkerPayoutDtoMapperImpl mapper = new WorkerPayoutDtoMapperImpl();
        mapper.setLedgerEntriesProvider(id -> null);
        assertThat(mapper.toDto(payout(42L)).ledgerEntries()).isEmpty();
        mapper.setLedgerEntriesProvider(null);
        assertThat(mapper.toDto(payout(42L)).ledgerEntries()).isEmpty();
    }

    private static WorkerPayoutEntity payout(Long id) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setId(id);
        UserEntity worker = new UserEntity();
        worker.setId(500L);
        worker.setName("Jan");
        p.setWorker(worker);
        p.setPayoutDate(LocalDate.of(2026, 5, 1));
        p.setPeriodFrom(LocalDate.of(2026, 4, 1));
        p.setPeriodTo(LocalDate.of(2026, 4, 30));
        p.setAmount(new BigDecimal("100.00"));
        p.setSuggestedAmount(new BigDecimal("100.00"));
        p.setAdvanceApplied(BigDecimal.ZERO);
        p.setPaymentChannel(PaymentChannel.BANK_TRANSFER);
        p.setStatus(PayoutStatus.PAID);
        return p;
    }
}
