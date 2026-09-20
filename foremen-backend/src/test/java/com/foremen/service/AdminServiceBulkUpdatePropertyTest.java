package com.foremen.service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit + property coverage for the generic heterogeneous bulk update
 * {@link AdminService#update(java.util.List)} added for FOR-05-02 (task 1.1).
 *
 * <p>The bulk method must funnel each {@code (id, model)} pair through the existing single-row
 * {@link AdminService#update(Object, Object)} — so validation, the before-snapshot, {@code
 * updateFields}, the project-scope guard, and per-row audit all run identically — while the shared
 * {@code @Transactional} makes the whole batch atomic. This test exercises the real default methods
 * against an in-memory fake service (Mockito-backed DAO/mapper, no Spring/Testcontainers) and
 * asserts three things:
 *
 * <ul>
 *   <li><b>Per-row values</b> — each row receives its own model (not one model applied to many).</li>
 *   <li><b>Order preservation</b> — the returned list matches the input order.</li>
 *   <li><b>One audit per row</b> — exactly one {@code UPDATE} audit entry is written per item.</li>
 *   <li><b>Property (bulk)</b> — the batch result equals applying {@code update(id, model)} to each
 *       item in order.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 6.2</b>
 */
// Feature: FOR-05-02-rooms-dimensions, Property (bulk)
@Tag("Feature: FOR-05-02-rooms-dimensions, Property (bulk)")
class AdminServiceBulkUpdatePropertyTest {

    // --- Minimal domain used by the fake service ------------------------------------------------

    /** In-memory entity: an id and a single mutable value column. */
    static final class FakeEntity {
        Long id;
        String value;

        FakeEntity(Long id, String value) {
            this.id = id;
            this.value = value;
        }

        FakeEntity copy() {
            return new FakeEntity(id, value);
        }
    }

    /** Service model carrying the new value for a row (null value = "leave unchanged"). */
    static final class FakeModel {
        Long id;
        String value;

        FakeModel(String value) {
            this.value = value;
        }
    }

    // --- Fake in-memory AdminService ------------------------------------------------------------

    /**
     * A concrete {@link AdminService} whose collaborators are Mockito mocks backed by an in-memory
     * store, so the real {@code update(id, model)} and {@code update(List)} default methods execute
     * end to end. {@code updateFields} copies a non-null value onto the target entity (null-ignore),
     * mirroring the MapStruct null-ignore strategy; each {@code save} records the audited value.
     */
    static final class FakeAdminService
            implements AdminService<FakeModel, FakeModel, FakeEntity, Long> {

        private final Map<Long, FakeEntity> store = new LinkedHashMap<>();
        private final AdminDao<FakeEntity, Long> dao;
        private final ServiceToDaoMapper<FakeEntity, FakeModel, FakeModel> mapper;
        private final AuditLogDao auditLogDao;
        private final EntityManager entityManager;

        /** Records one entry per audit write: the entity id and its value at save time. */
        final List<AuditLogEntity> auditLog = new ArrayList<>();

        @SuppressWarnings("unchecked")
        FakeAdminService() {
            this.dao = mock(AdminDao.class);
            this.mapper = mock(ServiceToDaoMapper.class);
            this.auditLogDao = mock(AuditLogDao.class);
            this.entityManager = mock(EntityManager.class);

            when(dao.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
            when(dao.save(any(FakeEntity.class))).thenAnswer(inv -> {
                FakeEntity e = inv.getArgument(0);
                store.put(e.id, e);
                return e;
            });

            // updateFields: copy the model's non-null value onto the target entity.
            doAnswer(inv -> {
                FakeModel src = inv.getArgument(0);
                FakeEntity target = inv.getArgument(1);
                if (src.value != null) {
                    target.value = src.value;
                }
                return null;
            }).when(mapper).updateFields(any(FakeModel.class), any(FakeEntity.class));

            // toServiceExtendedModel: read the entity's current value back into a model.
            when(mapper.toServiceExtendedModel(any(FakeEntity.class))).thenAnswer(inv -> {
                FakeEntity e = inv.getArgument(0);
                FakeModel m = new FakeModel(e.value);
                m.id = e.id;
                return m;
            });

            when(auditLogDao.save(any(AuditLogEntity.class))).thenAnswer(inv -> {
                auditLog.add(inv.getArgument(0));
                return inv.getArgument(0);
            });
        }

        /** Seeds an entity into the store and returns its id. */
        Long seed(Long id, String value) {
            store.put(id, new FakeEntity(id, value));
            return id;
        }

        String currentValue(Long id) {
            return store.get(id).value;
        }

        @Override
        public AdminDao<FakeEntity, Long> getDao() {
            return dao;
        }

        @Override
        public AuditLogDao getAuditLogDao() {
            return auditLogDao;
        }

        @Override
        public ReadOnlyAdminDao<FakeEntity, Long> getReadDao() {
            return dao;
        }

        @Override
        public ServiceToDaoMapper<FakeEntity, FakeModel, FakeModel> getMapper() {
            return mapper;
        }

        @Override
        public EntityManager getEntityManager() {
            return entityManager;
        }

        // serializeEntity uses Jackson on the entity graph; FakeEntity is a trivial POJO so the
        // default is fine, but keep the audit snapshot cheap and deterministic.
        @Override
        public String serializeEntity(FakeEntity entity) {
            return entity == null ? null : "{\"id\":" + entity.id + ",\"value\":\"" + entity.value + "\"}";
        }
    }

    // --- Unit tests -----------------------------------------------------------------------------

    @Test
    @DisplayName("update(List) applies each row's own value (not one model to many) and preserves order")
    void bulkUpdate_appliesPerRowValues_inOrder() {
        FakeAdminService service = new FakeAdminService();
        Long id1 = service.seed(1L, "a");
        Long id2 = service.seed(2L, "b");
        Long id3 = service.seed(3L, "c");

        List<AdminService.IdModel<Long, FakeModel>> items = List.of(
                new AdminService.IdModel<>(id3, new FakeModel("z")),
                new AdminService.IdModel<>(id1, new FakeModel("x")),
                new AdminService.IdModel<>(id2, new FakeModel("y")));

        List<FakeModel> result = service.update(items);

        // Order preserved: result[i] corresponds to items[i].
        assertThat(result).extracting(m -> m.id).containsExactly(id3, id1, id2);
        assertThat(result).extracting(m -> m.value).containsExactly("z", "x", "y");

        // Per-row values persisted (each row got its OWN value, not a shared one).
        assertThat(service.currentValue(id1)).isEqualTo("x");
        assertThat(service.currentValue(id2)).isEqualTo("y");
        assertThat(service.currentValue(id3)).isEqualTo("z");
    }

    @Test
    @DisplayName("update(List) writes exactly one UPDATE audit entry per row")
    void bulkUpdate_writesOneAuditPerRow() {
        FakeAdminService service = new FakeAdminService();
        Long id1 = service.seed(10L, "a");
        Long id2 = service.seed(20L, "b");

        service.update(List.of(
                new AdminService.IdModel<>(id1, new FakeModel("a2")),
                new AdminService.IdModel<>(id2, new FakeModel("b2"))));

        assertThat(service.auditLog).hasSize(2);
        assertThat(service.auditLog).allSatisfy(entry ->
                assertThat(entry.getOperation()).isEqualTo("UPDATE"));
        assertThat(service.auditLog).extracting(AuditLogEntity::getEntityId)
                .containsExactly(id1, id2);
    }

    @Test
    @DisplayName("update(List) with an empty list is a no-op returning an empty result")
    void bulkUpdate_emptyList_isNoOp() {
        FakeAdminService service = new FakeAdminService();

        List<FakeModel> result = service.update(List.<AdminService.IdModel<Long, FakeModel>>of());

        assertThat(result).isEmpty();
        assertThat(service.auditLog).isEmpty();
    }

    // --- Property (bulk) ------------------------------------------------------------------------

    /**
     * For a random list of {@code (id, partial update)} the batch {@code update(List)} produces the
     * same persisted state, the same ordered result values, and the same number of audit writes as
     * applying {@code update(id, model)} to each item sequentially in order.
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-05-02-rooms-dimensions, Property (bulk)")
    void bulkUpdate_equalsSequentialPerRowUpdate(
            @ForAll("updateItems") List<int[]> rawItems) {

        // Two independently seeded services with identical starting state.
        FakeAdminService batch = new FakeAdminService();
        FakeAdminService sequential = new FakeAdminService();
        // Seed ids 0..MAX with a deterministic initial value on both.
        for (long id = 0; id <= MAX_ID; id++) {
            batch.seed(id, "init-" + id);
            sequential.seed(id, "init-" + id);
        }

        // Build the (id, model) items; encode "null value = leave unchanged" via the flag column.
        List<AdminService.IdModel<Long, FakeModel>> items = new ArrayList<>();
        for (int[] row : rawItems) {
            long id = row[0];
            boolean hasValue = row[1] == 1;
            int valueSeed = row[2];
            String value = hasValue ? "v" + valueSeed : null;
            items.add(new AdminService.IdModel<>(id, new FakeModel(value)));
        }

        // Batch path.
        List<FakeModel> batchResult = batch.update(items);

        // Sequential path: apply each item in order via the single-row update.
        List<FakeModel> sequentialResult = new ArrayList<>();
        for (AdminService.IdModel<Long, FakeModel> item : items) {
            sequentialResult.add(sequential.update(item.id(), item.model()));
        }

        // Ordered result values match.
        assertThat(batchResult).extracting(m -> m.value)
                .containsExactlyElementsOf(sequentialResult.stream().map(m -> m.value).toList());
        assertThat(batchResult).extracting(m -> m.id)
                .containsExactlyElementsOf(sequentialResult.stream().map(m -> m.id).toList());

        // Final persisted state matches for every touched id.
        Map<Long, String> expectedState = new HashMap<>();
        for (long id = 0; id <= MAX_ID; id++) {
            expectedState.put(id, sequential.currentValue(id));
        }
        for (long id = 0; id <= MAX_ID; id++) {
            assertThat(batch.currentValue(id))
                    .as("persisted value for id %d matches sequential application", id)
                    .isEqualTo(expectedState.get(id));
        }

        // One audit per row, same count on both paths.
        assertThat(batch.auditLog).hasSize(items.size());
        assertThat(batch.auditLog.size()).isEqualTo(sequential.auditLog.size());
    }

    // --- Generators -----------------------------------------------------------------------------

    private static final int MAX_ID = 5;

    /**
     * A list (0..12 items) of update rows encoded as {@code [id, hasValueFlag, valueSeed]}. Ids are
     * drawn from the seeded range {@code [0, MAX_ID]} so every row resolves to an existing entity;
     * duplicate ids are allowed so the "last write wins" ordering is exercised.
     */
    @Provide
    Arbitrary<List<int[]>> updateItems() {
        Arbitrary<Integer> id = Arbitraries.integers().between(0, MAX_ID);
        Arbitrary<Integer> hasValue = Arbitraries.integers().between(0, 1);
        Arbitrary<Integer> valueSeed = Arbitraries.integers().between(0, 1_000);
        Arbitrary<int[]> row = Combinators.combine(id, hasValue, valueSeed)
                .as((i, h, v) -> new int[]{i, h, v});
        return row.list().ofMaxSize(12);
    }
}
