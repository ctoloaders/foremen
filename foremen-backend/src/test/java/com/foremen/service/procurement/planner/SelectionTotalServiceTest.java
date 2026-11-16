package com.foremen.service.procurement.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.SelectionTotalResponse;
import com.foremen.controller.model.SelectionTotalResponse.SkipReason;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionAudienceResolver;

/** Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5 */
class SelectionTotalServiceTest {

    private PurchasePositionDao dao;
    private ProcurementProjectAccess access;
    private PositionAudienceResolver audience;
    private SelectionTotalService service;

    @BeforeEach
    void setUp() {
        dao = mock(PurchasePositionDao.class);
        access = mock(ProcurementProjectAccess.class);
        audience = mock(PositionAudienceResolver.class);
        service = new SelectionTotalService(dao, access, audience);
        when(audience.isStaffFull()).thenReturn(true);
        when(access.allowedProjectIds(anyLong())).thenReturn(Set.of(1L, 2L));
        authenticate("10", "ROLE_MANAGER");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sumsCountedPositionsPerRateAndClassifiesSkipped() {
        PurchasePositionEntity a = position(1L, 1L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "10.00", "2", 23);
        PurchasePositionEntity b = position(2L, 1L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "5.00", "3", 8);
        PurchasePositionEntity notAccepted = position(3L, 1L, SourcingPath.AGREED, PositionStatus.CANCELLED, "1", "1", 23);
        PurchasePositionEntity notAgreed = position(4L, 1L, SourcingPath.PACKAGE, PositionStatus.ACCEPTED, "1", "1", 23);
        PurchasePositionEntity noPrice = position(5L, 1L, SourcingPath.AGREED, PositionStatus.ACCEPTED, null, "1", 23);
        PurchasePositionEntity foreign = position(6L, 99L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "1", "1", 23);
        when(dao.findAllById(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)))
                .thenReturn(List.of(a, b, notAccepted, notAgreed, noPrice, foreign));

        SelectionTotalResponse r = service.total(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 1L));

        assertThat(r.count()).isEqualTo(3);
        assertThat(r.unpriced()).isEqualTo(1);
        assertThat(r.saleNet()).isEqualByComparingTo("35.00");
        assertThat(r.vat()).isEqualByComparingTo("5.80"); // 4.60 + 1.20 (rounded per line)
        assertThat(r.saleGross()).isEqualByComparingTo("40.80");
        assertThat(r.vatBreakdown()).hasSize(2);
        assertThat(r.vatBreakdown().get(0).ratePercent()).isEqualByComparingTo("8");
        assertThat(r.vatBreakdown().get(1).ratePercent()).isEqualByComparingTo("23");
        assertThat(r.projectId()).isEqualTo(1L);
        assertThat(r.mixedProjects()).isFalse();
        assertThat(r.skipped()).extracting(s -> s.positionId() + ":" + s.reason()).containsExactly(
                "3:" + SkipReason.NOT_ACCEPTED, "4:" + SkipReason.NOT_AGREED,
                "6:" + SkipReason.NOT_FOUND, "7:" + SkipReason.NOT_FOUND);
    }

    @Test
    void countsEveryAgreedStatusExceptCancelled() {
        List<PositionStatus> counted = new ArrayList<>(List.of(PositionStatus.values()));
        counted.remove(PositionStatus.CANCELLED);
        List<PurchasePositionEntity> all = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        long id = 1;
        for (PositionStatus s : PositionStatus.values()) {
            all.add(position(id, 1L, SourcingPath.AGREED, s, "10", "1", 23));
            ids.add(id++);
        }
        when(dao.findAllById(ids)).thenReturn(all);

        SelectionTotalResponse r = service.total(ids);

        assertThat(r.count()).isEqualTo(counted.size());
        assertThat(r.unpriced()).isZero();
        assertThat(r.skipped()).hasSize(1);
    }

    @Test
    void flagsMixedProjects() {
        PurchasePositionEntity a = position(1L, 1L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "10", "1", 23);
        PurchasePositionEntity b = position(2L, 2L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "10", "1", 23);
        when(dao.findAllById(List.of(1L, 2L))).thenReturn(List.of(a, b));

        SelectionTotalResponse r = service.total(List.of(1L, 2L));

        assertThat(r.mixedProjects()).isTrue();
        assertThat(r.projectId()).isNull();
        assertThat(r.count()).isEqualTo(2);
    }

    @Test
    void rejectsNonStaffAudienceBeforeAnythingElse() {
        when(audience.isStaffFull()).thenReturn(false);

        assertThatThrownBy(() -> service.total(null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).isEqualTo("error.access.denied");
                });
    }

    @Test
    void rejectsEmptyAndTooLargeSelections() {
        assertThatThrownBy(() -> service.total(List.of()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("error.purchasePlanner.selection.empty"));

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            tooMany.add(i);
        }
        assertThatThrownBy(() -> service.total(tooMany))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("error.purchasePlanner.selection.tooLarge"));
    }

    @Test
    void adminSeesEveryProject() {
        authenticate("1", "ROLE_ADMIN");
        PurchasePositionEntity foreign = position(6L, 99L, SourcingPath.AGREED, PositionStatus.ACCEPTED, "1", "1", 23);
        when(dao.findAllById(List.of(6L))).thenReturn(List.of(foreign));

        assertThat(service.total(List.of(6L)).count()).isEqualTo(1);
    }

    private static void authenticate(String userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    private static PurchasePositionEntity position(Long id, Long projectId, SourcingPath path, PositionStatus status,
                                                   String salePrice, String qty, int vatPercent) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        p.setProject(project);
        p.setSourcingPath(path);
        p.setStatus(status);
        p.setSalePrice(salePrice == null ? null : new BigDecimal(salePrice));
        p.setQuantity(new BigDecimal(qty));
        VatRateEntity vat = new VatRateEntity();
        vat.setRate(BigDecimal.valueOf(vatPercent));
        p.setSaleVatRate(vat);
        return p;
    }
}
