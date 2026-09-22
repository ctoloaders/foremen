package com.foremen.service;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.mapper.WorkPriceServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * Unit tests for {@link WorkPriceService}'s audit-snapshot override (FOR-05-04, Requirement 1).
 *
 * <p>{@link WorkPriceEntity} is a flat single-price row: {@code (workItem, currency, netPrice)}. The
 * service overrides {@code serializeEntity} to produce a FLAT snapshot for every state:
 * {@code { id, workItemId, currencyCode, netPrice }}. These tests drive the service with mocked
 * collaborators and capture the {@link AuditLogEntity} via {@link ArgumentCaptor} to assert the flat
 * snapshots carry the right values.
 *
 * Feature: FOR-05-04-estimate-packages-changes (audit snapshot representation)
 */
class WorkPriceAuditSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkPriceDao dao;
    private WorkPriceServiceMapper mapper;
    private AuditLogDao auditLogDao;
    private EntityManager entityManager;
    private WorkPriceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dao = mock(WorkPriceDao.class);
        mapper = mock(WorkPriceServiceMapper.class);
        auditLogDao = mock(AuditLogDao.class);
        entityManager = mock(EntityManager.class);
        service = new WorkPriceService(dao, mapper, auditLogDao, entityManager);

        when(mapper.toServiceExtendedModel(any())).thenReturn(
                new WorkPriceServiceExtendedModel(1L, 1L, BigDecimal.ZERO));
    }

    // --- Fixture helpers ---

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

    private static WorkPriceEntity workPrice(long id, long workItemId, String currencyCode, String net) {
        WorkPriceEntity wp = new WorkPriceEntity();
        wp.setId(id);
        wp.setWorkItem(workItem(workItemId));
        wp.setCurrency(currency(1L, currencyCode));
        wp.setNetPrice(new BigDecimal(net));
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
    @DisplayName("CREATE writes flat snapshot with workItemId/currencyCode/netPrice")
    void createProducesValidSnapshot() {
        WorkPriceEntity entity = workPrice(5L, 100L, "PLN", "100.00");
        when(mapper.toCreateDaoModel(any())).thenReturn(entity);
        when(dao.save(any(WorkPriceEntity.class))).thenReturn(entity);

        service.create(new WorkPriceServiceExtendedModel(100L, 1L, new BigDecimal("100.00")));

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("CREATE");
        assertThat(row.getEntityClass()).isEqualTo("WorkPriceEntity");
        assertThat(row.getEntityId()).isEqualTo(5L);
        assertThat(row.getSnapshotBefore()).isNull();
        assertNoSerializationFailure(row.getSnapshotAfter());

        JsonNode after = parse(row.getSnapshotAfter());
        assertThat(after.get("workItemId").asLong()).isEqualTo(100L);
        assertThat(after.get("currencyCode").asText()).isEqualTo("PLN");
        assertThat(after.get("netPrice").decimalValue()).isEqualByComparingTo("100.00");
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE writes flat before snapshot, null after")
    void deleteProducesValidSnapshot() {
        WorkPriceEntity entity = workPrice(7L, 200L, "PLN", "150.00");
        when(dao.findById(7L)).thenReturn(Optional.of(entity));

        service.deleteById(7L);

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("DELETE");
        assertThat(row.getEntityId()).isEqualTo(7L);
        assertThat(row.getSnapshotAfter()).isNull();
        assertNoSerializationFailure(row.getSnapshotBefore());
        JsonNode before = parse(row.getSnapshotBefore());
        assertThat(before.get("workItemId").asLong()).isEqualTo(200L);
        assertThat(before.get("netPrice").decimalValue()).isEqualByComparingTo("150.00");
    }

    // --- UPDATE: price-only change (flat symmetric before/after) ---

    @Test
    @DisplayName("price-only UPDATE yields flat symmetric before/after carrying old/new netPrice")
    void priceOnlyUpdateProducesSymmetricFlatSnapshots() {
        WorkPriceEntity existing = workPrice(9L, 300L, "PLN", "100.00");
        when(dao.findById(9L)).thenReturn(Optional.of(existing));
        when(dao.save(any(WorkPriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // Simulate the mapper mutating ONLY netPrice: 100 -> 120.
        doAnswer(inv -> {
            WorkPriceEntity target = inv.getArgument(1);
            target.setNetPrice(new BigDecimal("120.00"));
            return null;
        }).when(mapper).updateFields(any(), any(WorkPriceEntity.class));

        service.update(9L, new WorkPriceServiceExtendedModel(300L, 1L, new BigDecimal("120.00")));

        AuditLogEntity row = captureSingleAudit();
        assertThat(row.getOperation()).isEqualTo("UPDATE");
        assertNoSerializationFailure(row.getSnapshotBefore());
        assertNoSerializationFailure(row.getSnapshotAfter());

        JsonNode before = parse(row.getSnapshotBefore());
        JsonNode after = parse(row.getSnapshotAfter());

        assertThat(before.get("workItemId").asLong()).isEqualTo(300L);
        assertThat(after.get("workItemId").asLong()).isEqualTo(300L);

        assertThat(before.get("netPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.get("netPrice").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("workItem-changed UPDATE still writes the flat snapshot with the new workItemId")
    void workItemChangeProducesFlatSnapshot() {
        WorkPriceEntity existing = workPrice(13L, 500L, "PLN", "100.00");
        when(dao.findById(13L)).thenReturn(Optional.of(existing));
        when(dao.save(any(WorkPriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            WorkPriceEntity target = inv.getArgument(1);
            target.setWorkItem(workItem(999L)); // work item changed
            return null;
        }).when(mapper).updateFields(any(), any(WorkPriceEntity.class));

        service.update(13L, new WorkPriceServiceExtendedModel(999L, 1L, new BigDecimal("100.00")));

        AuditLogEntity row = captureSingleAudit();
        JsonNode after = parse(row.getSnapshotAfter());
        assertThat(after.get("workItemId").asLong()).isEqualTo(999L);
        assertThat(after.get("netPrice").decimalValue()).isEqualByComparingTo("100.00");
    }
}
