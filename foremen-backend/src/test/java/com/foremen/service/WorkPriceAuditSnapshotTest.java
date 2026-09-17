package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.mapper.WorkPriceServiceMapper;
import com.foremen.service.query.CustomQueryResolverRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link WorkPriceService}'s audit-snapshot override (FOR-04-12b).
 *
 * <p>The generic {@code AdminService} audit path serialized the whole {@link WorkPriceEntity} with a
 * shared mapper and hit the {@code packagePrices -> workPrice -> packagePrices ...} cycle, storing the
 * useless fallback {@code {"error":"serialization_failed","class":"WorkPriceEntity"}}. The service now
 * overrides only {@code serializeEntity} to produce a FLAT, SYMMETRIC snapshot for every state:
 * {@code { id, workItemId, <packageCode>: netPrice, ... }}. Because before and after share this shape,
 * the audit UI's generic top-level diff yields one changed row per differing package. These tests drive
 * the service with mocked collaborators and capture the {@link AuditLogEntity} via
 * {@link ArgumentCaptor} to assert the flat snapshots carry the right values.
 *
 * Feature: FOR-04-12b work-prices-packages (audit snapshot representation)
 */
class WorkPriceAuditSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkPriceDao dao;
    private WorkPriceServiceMapper mapper;
    private AuditLogDao auditLogDao;
    private EntityManager entityManager;
    private CustomQueryResolverRegistry registry;
    private WorkPriceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dao = mock(WorkPriceDao.class);
        mapper = mock(WorkPriceServiceMapper.class);
        auditLogDao = mock(AuditLogDao.class);
        entityManager = mock(EntityManager.class);
        registry = mock(CustomQueryResolverRegistry.class);
        service = new WorkPriceService(dao, mapper, auditLogDao, entityManager, registry);

        when(mapper.toServiceExtendedModel(any())).thenReturn(
                new WorkPriceServiceExtendedModel(1L, List.of()));
    }

    // --- Fixture helpers ---

    private static OfferPackageEntity pkg(long id, String code) {
        OfferPackageEntity p = new OfferPackageEntity();
        p.setId(id);
        p.setCode(code);
        p.setNameRU(code + "_ru");
        p.setNamePL(code + "_pl");
        return p;
    }

    private static CurrencyEntity currency(long id, String code) {
        CurrencyEntity c = new CurrencyEntity();
        c.setId(id);
        c.setCode(code);
        return c;
    }

    private static WorkItemEntity workItem(long id) {
        WorkItemEntity w = new WorkItemEntity();
        w.setId(id);
        return w;
    }

    private static WorkPackagePriceEntity price(WorkPriceEntity parent,
                                                OfferPackageEntity pkg,
                                                CurrencyEntity currency,
                                                String net) {
        WorkPackagePriceEntity m = new WorkPackagePriceEntity();
        m.setWorkPrice(parent);
        m.setOfferPackage(pkg);
        m.setCurrency(currency);
        m.setNetPrice(new BigDecimal(net));
        return m;
    }

    /** Builds a WorkPriceEntity with a real back-referencing (cyclic) package-price graph. */
    private static WorkPriceEntity workPrice(long id, long workItemId, String[][] pkgPrices) {
        WorkPriceEntity wp = new WorkPriceEntity();
        wp.setId(id);
        wp.setWorkItem(workItem(workItemId));
        CurrencyEntity pln = currency(1L, "PLN");
        List<WorkPackagePriceEntity> members = new ArrayList<>();
        long pid = 10;
        for (String[] pair : pkgPrices) {
            members.add(price(wp, pkg(pid++, pair[0]), pln, pair[1]));
        }
        wp.setPackagePrices(members);
        return wp;
    }

    private AuditLogEntity captureSingleAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private static void assertNoSerializationFailure(String json) {
        if (json != null) {
            assertThat(json).doesNotContain("serialization_failed");
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("snapshot is not valid JSON: " + json, e);
        }
    }

    // --- CREATE ---

    @Test
    @DisplayName("CREATE writes flat cycle-free snapshot with a key per package code")
    void createProducesValidSnapshot() {
        WorkPriceEntity entity = workPrice(5L, 100L, new String[][] {{"budget", "100.00"}, {"lux", "300.00"}});
        when(mapper.toCreateDaoModel(any())).thenReturn(entity);
        when(dao.save(any(WorkPriceEntity.class))).thenReturn(entity);

        service.create(new WorkPriceServiceExtendedModel(100L, List.of()));

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("CREATE");
        assertThat(row.getEntityClass()).isEqualTo("WorkPriceEntity");
        assertThat(row.getEntityId()).isEqualTo(5L);
        assertThat(row.getSnapshotBefore()).isNull();
        assertNoSerializationFailure(row.getSnapshotAfter());

        JsonNode after = parse(row.getSnapshotAfter());
        assertThat(after.get("workItemId").asLong()).isEqualTo(100L);
        // Flat: one top-level key per package code, value = netPrice. No nested array.
        assertThat(after.has("packagePrices")).isFalse();
        assertThat(after.get("budget").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.get("lux").decimalValue()).isEqualByComparingTo("300.00");
        // No back-reference leaked into the snapshot.
        assertThat(row.getSnapshotAfter()).doesNotContain("workPrice");
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE writes flat before snapshot, null after")
    void deleteProducesValidSnapshot() {
        WorkPriceEntity entity = workPrice(7L, 200L, new String[][] {{"budget", "150.00"}});
        when(dao.findById(7L)).thenReturn(Optional.of(entity));

        service.deleteById(7L);

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("DELETE");
        assertThat(row.getEntityId()).isEqualTo(7L);
        assertThat(row.getSnapshotAfter()).isNull();
        assertNoSerializationFailure(row.getSnapshotBefore());
        JsonNode before = parse(row.getSnapshotBefore());
        assertThat(before.get("workItemId").asLong()).isEqualTo(200L);
        assertThat(before.has("packagePrices")).isFalse();
        assertThat(before.get("budget").decimalValue()).isEqualByComparingTo("150.00");
    }

    // --- UPDATE: price-only change (flat symmetric before/after) ---

    @Test
    @DisplayName("price-only UPDATE yields flat symmetric before/after carrying per-package old/new values")
    void priceOnlyUpdateProducesSymmetricFlatSnapshots() {
        WorkPriceEntity existing = workPrice(9L, 300L,
                new String[][] {{"budget", "100.00"}, {"lux", "300.00"}});
        when(dao.findById(9L)).thenReturn(Optional.of(existing));
        when(dao.save(any(WorkPriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // Simulate the mapper mutating ONLY netPrice values (same workItem, same package set):
        // budget 100 -> 120 (changed), lux 300 -> 300 (unchanged).
        doAnswer(inv -> {
            WorkPriceEntity target = inv.getArgument(1);
            target.getPackagePrices().get(0).setNetPrice(new BigDecimal("120.00"));
            return null;
        }).when(mapper).updateFields(any(), any(WorkPriceEntity.class));

        service.update(9L, new WorkPriceServiceExtendedModel(300L, List.of()));

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("UPDATE");
        assertNoSerializationFailure(row.getSnapshotBefore());
        assertNoSerializationFailure(row.getSnapshotAfter());

        // Both snapshots are flat and symmetric; no priceChanges/packagePrices keys.
        JsonNode before = parse(row.getSnapshotBefore());
        JsonNode after = parse(row.getSnapshotAfter());
        assertThat(before.has("priceChanges")).isFalse();
        assertThat(after.has("priceChanges")).isFalse();
        assertThat(before.has("packagePrices")).isFalse();
        assertThat(after.has("packagePrices")).isFalse();

        assertThat(before.get("workItemId").asLong()).isEqualTo(300L);
        assertThat(after.get("workItemId").asLong()).isEqualTo(300L);

        // budget changed 100.00 -> 120.00; lux unchanged 300.00 in both.
        assertThat(before.get("budget").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.get("budget").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(before.get("lux").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(after.get("lux").decimalValue()).isEqualByComparingTo("300.00");
    }

    // --- UPDATE: structural change (still just the flat snapshot) ---

    @Test
    @DisplayName("structural UPDATE (package added) still writes the flat snapshot with the new package key")
    void structuralUpdateProducesFlatSnapshot() {
        WorkPriceEntity existing = workPrice(11L, 400L, new String[][] {{"budget", "100.00"}});
        when(dao.findById(11L)).thenReturn(Optional.of(existing));
        when(dao.save(any(WorkPriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // Simulate adding a new package (structure changed).
        doAnswer(inv -> {
            WorkPriceEntity target = inv.getArgument(1);
            target.getPackagePrices().add(
                    price(target, pkg(99L, "lux"), currency(1L, "PLN"), "300.00"));
            return null;
        }).when(mapper).updateFields(any(), any(WorkPriceEntity.class));

        service.update(11L, new WorkPriceServiceExtendedModel(400L, List.of()));

        AuditLogEntity row = captureSingleAudit();
        assertNoSerializationFailure(row.getSnapshotAfter());
        JsonNode after = parse(row.getSnapshotAfter());
        // Flat form: package-code keys, no nested array / no diff object.
        assertThat(after.has("priceChanges")).isFalse();
        assertThat(after.has("packagePrices")).isFalse();
        assertThat(after.get("budget").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.get("lux").decimalValue()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("workItem-changed UPDATE still writes the flat snapshot with the new workItemId")
    void workItemChangeProducesFlatSnapshot() {
        WorkPriceEntity existing = workPrice(13L, 500L, new String[][] {{"budget", "100.00"}});
        when(dao.findById(13L)).thenReturn(Optional.of(existing));
        when(dao.save(any(WorkPriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            WorkPriceEntity target = inv.getArgument(1);
            target.setWorkItem(workItem(999L)); // work item changed
            return null;
        }).when(mapper).updateFields(any(), any(WorkPriceEntity.class));

        service.update(13L, new WorkPriceServiceExtendedModel(999L, List.of()));

        AuditLogEntity row = captureSingleAudit();
        JsonNode after = parse(row.getSnapshotAfter());
        assertThat(after.has("priceChanges")).isFalse();
        assertThat(after.has("packagePrices")).isFalse();
        assertThat(after.get("workItemId").asLong()).isEqualTo(999L);
        assertThat(after.get("budget").decimalValue()).isEqualByComparingTo("100.00");
    }
}
