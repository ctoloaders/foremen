package com.foremen.service.payroll.dto.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.payroll.PayoutLedgerEntriesProvider;
import com.foremen.service.payroll.dto.PayoutLedgerEntryDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

/**
 * FOR-06-11 (Requirements 1.4, 3.3, 11.1): MapStruct read mapper from the {@link WorkerPayoutEntity}
 * persistence root to the {@link WorkerPayoutDto} read model of {@code com.foremen.service.payroll.dto}.
 * Spring component (via {@link ForemenMapperConfig}); builder-less, so the record is populated through
 * its canonical constructor.
 *
 * <p>The scalar fields map straight through. The worker is flattened to {@code workerId}
 * ({@code worker.id}) and {@code workerName}, resolved from the referenced {@link UserEntity} with the
 * {@code ru → pl → code} fallback via {@link #workerName(UserEntity)} — the payout entity carries no
 * i18n columns of its own (Requirement 8.1). The {@code paymentChannel} / {@code status} enums and the
 * {@code isAdvance} flag are copied through for the frontend to localize (Requirements 2.3, 8.2).
 *
 * <p>The aggregate {@code PayrollView} + the per-worker {@code WorkerCreditDto} and the suggestion
 * DTOs ({@code PayoutSuggestionDto} / {@code PayoutLineDto}) are assembled in
 * {@code WorkerPayoutService} / {@code PayoutSuggestionService} (they fold FOR-06-10's cost and the
 * advance-credit running balance), not here.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkerPayoutDtoMapper {

    /**
     * FOR-07-04 (R16.3) — the ledger-links seam. Optional: when no {@link PayoutLedgerEntriesProvider}
     * bean exists (the bank ledger is absent) or the mapper is instantiated directly (unit tests),
     * {@link PayoutLedgerEntriesProvider#NONE} yields an empty {@code ledgerEntries}.
     */
    private PayoutLedgerEntriesProvider ledgerEntriesProvider = PayoutLedgerEntriesProvider.NONE;

    @Autowired(required = false)
    public void setLedgerEntriesProvider(PayoutLedgerEntriesProvider ledgerEntriesProvider) {
        this.ledgerEntriesProvider = ledgerEntriesProvider == null
                ? PayoutLedgerEntriesProvider.NONE : ledgerEntriesProvider;
    }

    @Mapping(target = "workerId", source = "worker.id")
    @Mapping(target = "workerName", expression = "java(workerName(entity.getWorker()))")
    @Mapping(target = "isAdvance", expression = "java(entity.isAdvance())")
    @Mapping(target = "ledgerEntries", expression = "java(ledgerEntries(entity.getId()))")
    public abstract WorkerPayoutDto toDto(WorkerPayoutEntity entity);

    public abstract List<WorkerPayoutDto> toDtos(List<WorkerPayoutEntity> entities);

    /** FOR-07-04 (R16.3) — the payout's linked ledger lines via the seam; never {@code null}. */
    protected List<PayoutLedgerEntryDto> ledgerEntries(Long payoutId) {
        if (payoutId == null) {
            return List.of();
        }
        List<PayoutLedgerEntryDto> entries = ledgerEntriesProvider.forPayout(payoutId);
        return entries == null ? List.of() : entries;
    }

    /**
     * The worker's localized display name ({@code ru → pl → code} fallback). A worker is a
     * {@link UserEntity}; its resolved display name is {@link UserEntity#getName()}, matching the
     * FOR-06-10 {@code WorkerCostService} convention (Requirement 8.1).
     */
    protected String workerName(UserEntity worker) {
        return worker == null ? null : worker.getName();
    }
}
