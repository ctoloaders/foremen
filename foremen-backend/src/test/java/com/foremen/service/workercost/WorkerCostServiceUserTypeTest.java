package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-06 task 4.1 — unit tests for {@link WorkerCostService} resolving the tier from the worker
 * <strong>user's</strong> own {@code workerType} + {@code seniorityPct} (not the project membership).
 *
 * <p>Fixture: base tier share 0.40, so rate 100.00 ⇒ {@code Base_Cost = 40.00}; the firm type has
 * {@code tierPct = 0.65}. Unit cost = {@code Base_Cost × (1 + tierUplift + seniorityPct)}.
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6</b>
 */
@ExtendWith(MockitoExtension.class)
class WorkerCostServiceUserTypeTest {

    private static final Long PROJECT_ID = 7L;
    private static final BigDecimal RATE = new BigDecimal("100.00");

    @Mock
    private WorkReportDao workReportDao;
    @Mock
    private WorkReportServiceMapper serviceMapper;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private UserDao userDao;
    @Mock
    private WorkerTypeDao workerTypeDao;

    private WorkerCostService service;
    private WorkerTypeEntity baseType;
    private WorkerTypeEntity firmType;

    @BeforeEach
    void setUp() {
        service = new WorkerCostService(workReportDao, serviceMapper, projectAccessCache, auditLogDao,
                entityManager, userDao, workerTypeDao);
        baseType = type(1L, "BASE", "0.40", true, true);
        firmType = type(2L, "FIRM", "0.65", false, true);
        lenient().when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType, firmType));
    }

    @Test
    @DisplayName("an active user type drives the tier: firm 0.65 ⇒ 40 × 1.65 = 66.00 (R2.1, R2.4)")
    void activeUserTypeDrivesTier() {
        UserEntity worker = user(10L, firmType, BigDecimal.ZERO);
        givenReports(report(1L, worker, RATE, "2"));

        WorkerCostLineDto line = single(service.costForProject(PROJECT_ID));

        assertThat(line.baseTierFallback()).isFalse();
        assertThat(line.workerTypeId()).isEqualTo(firmType.getId());
        assertThat(line.workerTypeName()).isEqualTo("FIRM");
        assertThat(line.seniorityPct()).isEqualByComparingTo("0");
        assertThat(line.unitServiceCost()).isEqualByComparingTo("66.00");
        assertThat(line.costValue()).isEqualByComparingTo("132.00");
    }

    @Test
    @DisplayName("seniority adds to the tier uplift: firm 0.65 + 0.05 ⇒ 40 × 1.70 = 68.00 (R2.3)")
    void seniorityAddsToUplift() {
        UserEntity worker = user(10L, firmType, new BigDecimal("0.05"));
        givenReports(report(1L, worker, RATE, "1"));

        WorkerCostLineDto line = single(service.costForProject(PROJECT_ID));

        assertThat(line.baseTierFallback()).isFalse();
        assertThat(line.seniorityPct()).isEqualByComparingTo("0.05");
        assertThat(line.unitServiceCost()).isEqualByComparingTo("68.00");
        assertThat(line.costValue()).isEqualByComparingTo("68.00");
    }

    @Test
    @DisplayName("an active base user type with seniority 0 costs at the base tier, not flagged (R2.4)")
    void baseUserTypeNoSeniority() {
        UserEntity worker = user(10L, baseType, BigDecimal.ZERO);
        givenReports(report(1L, worker, RATE, "1"));

        WorkerCostLineDto line = single(service.costForProject(PROJECT_ID));

        assertThat(line.baseTierFallback()).isFalse();
        assertThat(line.workerTypeId()).isEqualTo(baseType.getId());
        assertThat(line.unitServiceCost()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("no user type ⇒ base tier (+ seniority), flagged fallback: 40 × 1.10 = 44.00 (R2.2, R2.3)")
    void noUserTypeFallsBackWithSeniority() {
        UserEntity worker = user(10L, null, new BigDecimal("0.10"));
        givenReports(report(1L, worker, RATE, "1"));

        WorkerCostLineDto line = single(service.costForProject(PROJECT_ID));

        assertThat(line.baseTierFallback()).isTrue();
        assertThat(line.workerTypeId()).isNull();
        assertThat(line.workerTypeName()).isEqualTo("BASE");
        assertThat(line.unitServiceCost()).isEqualByComparingTo("44.00");
    }

    @Test
    @DisplayName("an inactive user type ⇒ base-tier fallback, flagged (R2.2)")
    void inactiveUserTypeFallsBack() {
        WorkerTypeEntity dead = type(3L, "DEAD", "0.90", false, false);
        UserEntity worker = user(10L, dead, BigDecimal.ZERO);
        givenReports(report(1L, worker, RATE, "3"));

        WorkerCostLineDto line = single(service.costForProject(PROJECT_ID));

        assertThat(line.baseTierFallback()).isTrue();
        assertThat(line.workerTypeId()).isNull();
        assertThat(line.unitServiceCost()).isEqualByComparingTo("40.00");
        assertThat(line.costValue()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("a zero rate or a null quantity yields an unavailable (null) cost, never zero (R2.6)")
    void unavailableCostStaysNull() {
        UserEntity worker = user(10L, firmType, new BigDecimal("0.05"));
        WorkReportEntity zeroRate = report(1L, worker, BigDecimal.ZERO, "2");
        WorkReportEntity nullQty = report(2L, worker, RATE, null);
        givenReports(zeroRate, nullQty);

        WorkerCostReadModelDto dto = service.costForProject(PROJECT_ID);

        assertThat(dto.lines()).hasSize(2);
        assertThat(dto.lines().get(0).unitServiceCost()).isNull();
        assertThat(dto.lines().get(0).costValue()).isNull();
        assertThat(dto.lines().get(1).costValue()).isNull();
        assertThat(dto.byWorker()).isEmpty();
    }

    @Test
    @DisplayName("workerProfilesById batches distinct workers in one query; empty input never queries")
    void workerProfilesAreBatched() {
        UserEntity a = user(10L, firmType, new BigDecimal("0.02"));
        UserEntity b = user(11L, null, null);
        when(userDao.findWorkerProfiles(anyCollection())).thenReturn(List.of(a, b));

        Map<Long, WorkerProfile> profiles = service.workerProfilesById(List.of(10L, 11L, 10L));

        assertThat(profiles).containsOnlyKeys(10L, 11L);
        assertThat(profiles.get(10L).workerType()).isSameAs(firmType);
        assertThat(profiles.get(10L).seniorityPct()).isEqualByComparingTo("0.02");
        assertThat(profiles.get(11L).workerType()).isNull();
        assertThat(profiles.get(11L).seniorityPct()).isEqualByComparingTo("0");
        verify(userDao, times(1)).findWorkerProfiles(Set.of(10L, 11L));

        assertThat(service.workerProfilesById(List.of())).isEmpty();
        verify(userDao, times(1)).findWorkerProfiles(any());
    }

    @Test
    @DisplayName("costFor resolves through the profile map; a missing worker costs at the base tier")
    void costForUsesProfileMap() {
        WorkerTypeTier baseTier = WorkerTypeTier.base(baseType.getTierPct());
        UserEntity worker = user(10L, firmType, new BigDecimal("0.05"));
        WorkReportEntity report = report(1L, worker, RATE, "1");
        Map<Long, WorkerProfile> profiles =
                Map.of(10L, new WorkerProfile(10L, firmType, new BigDecimal("0.05")));

        assertThat(service.costFor(report, baseTier, baseType, profiles)).isEqualByComparingTo("68.00");
        assertThat(service.costFor(report, baseTier, baseType, Map.of())).isEqualByComparingTo("40.00");
        assertThat(service.costFor(null, baseTier, baseType, profiles)).isNull();
    }

    @Test
    @DisplayName("a project without approved reports yields an empty read model and no user query")
    void emptyProject() {
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID)).thenReturn(List.of());

        WorkerCostReadModelDto dto = service.costForProject(PROJECT_ID);

        assertThat(dto.lines()).isEmpty();
        verify(userDao, never()).findWorkerProfiles(any());
    }

    // --- fixtures ------------------------------------------------------------------------------

    private void givenReports(WorkReportEntity... reports) {
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID))
                .thenReturn(List.of(reports));
        java.util.LinkedHashMap<Long, UserEntity> users = new java.util.LinkedHashMap<>();
        for (WorkReportEntity r : reports) {
            users.putIfAbsent(r.getWorker().getId(), r.getWorker());
        }
        when(userDao.findWorkerProfiles(anyCollection())).thenReturn(List.copyOf(users.values()));
    }

    private static WorkerCostLineDto single(WorkerCostReadModelDto dto) {
        assertThat(dto.lines()).hasSize(1);
        return dto.lines().get(0);
    }

    private static WorkerTypeEntity type(Long id, String code, String tierPct, boolean base, boolean active) {
        WorkerTypeEntity t = new WorkerTypeEntity();
        t.setId(id);
        t.setCode(code);
        t.setTierPct(new BigDecimal(tierPct));
        t.setBase(base);
        t.setActive(active);
        return t;
    }

    private static UserEntity user(Long id, WorkerTypeEntity type, BigDecimal seniority) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setName("w" + id);
        u.setWorkerType(type);
        u.setSeniorityPct(seniority);
        return u;
    }

    private static WorkReportEntity report(Long id, UserEntity worker, BigDecimal rate, String qty) {
        WorkReportEntity r = new WorkReportEntity();
        r.setId(id);
        r.setWorker(worker);
        r.setClientUnitPrice(rate);
        r.setTotalQuantity(qty == null ? null : new BigDecimal(qty));
        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(100L);
        wi.setCode("WI");
        r.setWorkItem(wi);
        return r;
    }
}
