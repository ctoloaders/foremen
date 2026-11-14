package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.procurement.PurchaseRequestAssignedEvent;

/**
 * FOR-11-01 task 6.3 - proof of the atomic rollback of {@code POST /api/purchase-requests/from-positions}.
 *
 * <p>The shared base is {@code @Transactional}, which hides a physical rollback (the service joins the
 * test transaction). This variant opts out with {@code NOT_SUPPORTED}, commits its fixture through a
 * {@link TransactionTemplate}, calls the endpoint (the service opens and rolls back its own transaction)
 * and asserts, from fresh transactions, that no request was left behind and the free position is untouched.
 *
 * <p>Repeatability: the fixture is committed, so every test ends with a teardown that truncates all
 * tables of the shared test database (the other classes of the base only use rolled-back data and the
 * Liquibase seeds are off under the {@code integration-test} profile).
 *
 * <p>Validates Requirements 7.2, 7.3, 7.5.
 */
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestFromPositionsRollbackIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String URL = REQUESTS + "/from-positions";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEvents events;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** The base's {@code @BeforeEach} persists through the EntityManager and needs a (committed) transaction. */
    @Override
    @BeforeEach
    void seedMatrix() {
        tx().executeWithoutResult(s -> super.seedMatrix());
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void truncateAll() {
        tx().executeWithoutResult(s -> {
            List<String> tables = em.createNativeQuery(
                    "select tablename from pg_tables where schemaname = 'public'").getResultList();
            if (!tables.isEmpty()) {
                em.createNativeQuery("truncate table " + String.join(", ", tables) + " cascade").executeUpdate();
            }
        });
        for (String code : List.of("ADMIN", "MANAGER", "BUYER", "FOREMAN", "CLIENT", "WORKER")) {
            permissionCache.invalidate(code);
        }
    }

    private record Fixture(Long projectId, Long freeId, Long takenId, Long existingRequestId, Long buyerId) {}

    private Fixture fixture() {
        return tx().execute(s -> {
            ProjectEntity project = project(ProjectStatus.ACTIVE);
            UserEntity buyer = user("BUYER");
            PurchasePositionEntity free = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
            PurchasePositionEntity taken = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
            var existing = request(project);
            attach(taken, existing);
            return new Fixture(project.getId(), free.getId(), taken.getId(), existing.getId(), buyer.getId());
        });
    }

    private long requestCount(Long projectId) {
        return tx().execute(s -> em.createQuery(
                "select count(r) from PurchaseRequestEntity r where r.project.id = :id", Long.class)
                .setParameter("id", projectId).getSingleResult());
    }

    @Test
    @DisplayName("attach failure rolls the created request back: only the pre-existing request remains, free position unattached")
    void attachFailureLeavesNothingBehind() throws Exception {
        Fixture f = fixture();
        assertThat(requestCount(f.projectId())).isEqualTo(1);

        mockMvc.perform(post(URL).header("Accept-Language", "pl").contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionIds\": [" + f.freeId() + "," + f.takenId() + "], \"assignedBuyerId\": "
                        + f.buyerId() + "}"))
                .andExpect(status().isConflict());

        assertThat(requestCount(f.projectId())).as("only the pre-existing request remains").isEqualTo(1);
        tx().executeWithoutResult(s -> {
            PurchasePositionEntity free = em.find(PurchasePositionEntity.class, f.freeId());
            PurchasePositionEntity taken = em.find(PurchasePositionEntity.class, f.takenId());
            assertThat(free.getPurchaseRequest()).isNull();
            assertThat(taken.getPurchaseRequest().getId()).isEqualTo(f.existingRequestId());
        });
    }

    @Test
    @DisplayName("success commits the request and the attach together")
    void successCommits() throws Exception {
        Fixture f = fixture();

        mockMvc.perform(post(URL).header("Accept-Language", "pl").contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionIds\": [" + f.freeId() + "], \"assignedBuyerId\": " + f.buyerId() + "}"))
                .andExpect(status().isOk());

        assertThat(requestCount(f.projectId())).isEqualTo(2);
        tx().executeWithoutResult(s -> assertThat(
                em.find(PurchasePositionEntity.class, f.freeId()).getPurchaseRequest()).isNotNull());
        assertThat(events.stream(PurchaseRequestAssignedEvent.class).count()).isEqualTo(1);
    }
}
