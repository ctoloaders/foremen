package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.matrix.BranchSubtotals;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.EstimateMatrixWorkerMask;
import com.foremen.service.estimate.matrix.EstimatePackageSummaryDto;
import com.foremen.service.estimate.matrix.MoneyRange;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.payroll.PayrollWorkerScope;
import com.foremen.service.payroll.WorkerPayoutService;

/**
 * Bug reports of 2026-11-27: FOR-07-06 Round 2 (a WORKER reads only their own payroll) and FOR-04-19
 * Round 1 (no work price for a WORKER: DTO masking and the estimate matrix).
 */
class WorkerPriceAndPayrollScopeTest {

    private static void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // --- payroll ---

    @Test
    @DisplayName("payout addRequiredQuery: a WORKER gets the own-rows condition, a MANAGER none")
    void payoutRequiredQuery() {
        WorkerPayoutService service = mock(WorkerPayoutService.class, CALLS_REAL_METHODS);
        authenticate(15, "WORKER");
        assertThat(service.addRequiredQuery()).isNotNull();
        authenticate(13, "MANAGER");
        assertThat(service.addRequiredQuery()).isNull();
    }

    @Test
    @DisplayName("worker-keyed payroll reads: a WORKER may read only their own id; other roles any id")
    void workerScope() {
        authenticate(15, "WORKER");
        PayrollWorkerScope.assertMayRead(15L);
        assertThatThrownBy(() -> PayrollWorkerScope.assertMayRead(16L))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(e -> assertThat(((ForemenApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(PayrollWorkerScope.narrow(List.of())).containsExactly(15L);
        assertThat(PayrollWorkerScope.narrow(List.of(16L, 17L))).containsExactly(15L);

        authenticate(13, "MANAGER");
        PayrollWorkerScope.assertMayRead(16L);
        assertThat(PayrollWorkerScope.narrow(List.of(16L, 17L))).containsExactly(16L, 17L);
    }

    // --- work prices: generic DTO masking ---

    @Test
    @DisplayName("work price record: netPrice cleared for WORKER, kept for SALESMAN and MANAGER")
    void workPriceRecordMasked() {
        WorkPriceService service = mock(WorkPriceService.class, CALLS_REAL_METHODS);
        WorkPriceServiceExtendedModel model = new WorkPriceServiceExtendedModel(1L, 2L, new BigDecimal("99.50"));

        authenticate(15, "WORKER");
        WorkPriceServiceExtendedModel masked = service.applyAdminOnlyMask(model);
        assertThat(masked.netPrice()).isNull();
        assertThat(masked.workItemId()).isEqualTo(1L);
        assertThat(masked.currencyId()).isEqualTo(2L);

        authenticate(20, "SALESMAN");
        assertThat(service.applyAdminOnlyMask(model).netPrice()).isEqualByComparingTo("99.50");
        authenticate(13, "MANAGER");
        assertThat(service.applyAdminOnlyMask(model).netPrice()).isEqualByComparingTo("99.50");
    }

    @Test
    @DisplayName("getAdminOnlyFields returns the price fields only for the WORKER role")
    void adminOnlyFieldsWorkerOnly() {
        authenticate(15, "WORKER");
        assertThat(mock(WorkItemService.class, CALLS_REAL_METHODS).getAdminOnlyFields()).containsExactly("internalPrice");
        assertThat(mock(EstimateService.class, CALLS_REAL_METHODS).getAdminOnlyFields())
                .containsExactlyInAnyOrder("totalNet", "totalVat", "totalGross");
        assertThat(mock(EstimateLineService.class, CALLS_REAL_METHODS).getAdminOnlyFields())
                .containsExactlyInAnyOrder("unitPrice", "valueNet");
        authenticate(20, "SALESMAN");
        assertThat(mock(EstimateLineService.class, CALLS_REAL_METHODS).getAdminOnlyFields()).isEmpty();
    }

    // --- estimate matrix ---

    private static EstimateMatrixDto matrix() {
        MoneyRange labourPoint = MoneyRange.point(new BigDecimal("100"));
        MoneyRange cost = new MoneyRange(new BigDecimal("130"), new BigDecimal("150"));
        CellDto cell = new CellDto(1L, 9L, true, new BigDecimal("10"), "f", "k", false, false,
                new BigDecimal("100"), List.of(), cost, null, new BigDecimal("100"));
        WorkRowDto row = new WorkRowDto(1L, "Work", List.of(), List.of(cell), BigDecimal.ONE, labourPoint,
                "m2", "m²", new BigDecimal("10"));
        BranchSubtotals sub = new BranchSubtotals(labourPoint, MoneyRange.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto group = new WorkTypeGroupDto(5L, "Cat", List.of(row), sub, BigDecimal.ONE, labourPoint);
        EstimatePackageSummaryDto summary = new EstimatePackageSummaryDto(true, 3L, "PKG", new BigDecimal("500"),
                new BigDecimal("40"), new BigDecimal("20000"), new BigDecimal("100"), new BigDecimal("20100"));
        return new EstimateMatrixDto(7L, false, List.of(), List.of(group), sub, BigDecimal.TEN, BigDecimal.ZERO,
                MoneyRange.ZERO, "PKG", summary);
    }

    @Test
    @DisplayName("estimate matrix: work prices cleared for WORKER, materials and volumes kept")
    void matrixMaskedForWorker() {
        authenticate(15, "WORKER");
        EstimateMatrixDto masked = EstimateMatrixWorkerMask.apply(matrix());
        WorkRowDto row = masked.groups().get(0).rows().get(0);
        CellDto cell = row.cells().get(0);
        assertThat(row.unitPrice()).isNull();
        assertThat(row.baseUnitPrice()).isNull();
        assertThat(cell.labour()).isNull();
        assertThat(cell.baseLabour()).isNull();
        assertThat(cell.volume()).isEqualByComparingTo("10");
        // cost band without the labour part: 130..150 - 100 = 30..50 (materials only)
        assertThat(cell.costRange().min()).isEqualByComparingTo("30");
        assertThat(cell.costRange().max()).isEqualByComparingTo("50");
        assertThat(masked.totals().works()).isNull();
        assertThat(masked.totals().construction()).isEqualTo(MoneyRange.ZERO);
        assertThat(masked.groups().get(0).subtotals().works()).isNull();
        assertThat(masked.packageSummary().applied()).isTrue();
        assertThat(masked.packageSummary().total()).isNull();
        assertThat(masked.packageSummary().zlM2()).isNull();
    }

    @Test
    @DisplayName("estimate matrix: unchanged for a MANAGER")
    void matrixUnchangedForManager() {
        authenticate(13, "MANAGER");
        EstimateMatrixDto original = matrix();
        assertThat(EstimateMatrixWorkerMask.apply(original)).isSameAs(original);
    }
}
