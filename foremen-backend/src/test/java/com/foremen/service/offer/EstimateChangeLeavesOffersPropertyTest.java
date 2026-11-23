package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07b task 7.2 (D3, requirement 2.11): offers do not react to estimate changes.
 * Property: for every offer status and every estimate-change trigger (estimate save, room change, restore),
 * an estimate edit followed by the change event and a restore leaves every offer of the project
 * byte-identical (status, revision, totals, snapshot, inactive flag) and writes no audit row.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class EstimateChangeLeavesOffersPropertyTest extends OfferExplorationSupport {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private OfferService offerService;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private MockMvc mockMvc;

    /** Every field an estimate change could touch, plus the audit-row count of the project's offers. */
    private List<String> fingerprint(Long projectId) {
        return tx().execute(s -> {
            List<OfferEntity> offers = entityManager.createQuery(
                    "select o from OfferEntity o where o.project.id = :p order by o.id", OfferEntity.class)
                    .setParameter("p", projectId).getResultList();
            return offers.stream().map(o -> {
                try {
                    Long audits = entityManager.createQuery(
                            "select count(a) from AuditLogEntity a where a.entityClass = 'OfferEntity' "
                                    + "and a.entityId = :id and a.operation <> 'RESTORE_ESTIMATE'", Long.class)
                            .setParameter("id", o.getId()).getSingleResult();
                    return o.getId() + "|" + o.getStatus() + "|" + o.getRevision() + "|" + o.isInactive()
                            + "|" + o.getTotalNet() + "|" + o.getTotalVat() + "|" + o.getTotalGross()
                            + "|" + JSON.writeValueAsString(o.getSnapshot()) + "|audits=" + audits;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        });
    }

    private void publishCommitted(Long projectId, Long estimateId, EstimateChangedEvent.Trigger trigger) {
        // Inside a transaction so AFTER_COMMIT listeners (if any were left) would fire on commit.
        tx().executeWithoutResult(s -> publisher.publishEvent(
                new EstimateChangedEvent(projectId, estimateId, null, trigger)));
    }

    @ParameterizedTest
    @EnumSource(value = OfferStatus.class, names = {"DRAFT", "SENT", "COUNTERED", "CHANGES_REQUESTED", "APPROVED"})
    void estimateChangesLeaveEveryOfferUntouched(OfferStatus status) throws Exception {
        Fx f = emptyEstimateFixture("D3-" + status);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Base");
        OfferEntity other = offerService.prepareOffer(f.projectId, "Other");
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setStatus(status));
        List<String> before = fingerprint(f.projectId);
        assertThat(before).hasSize(2);

        // Estimate edits (save / package apply / labour adjustment all end in changed lines) + every trigger.
        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        for (EstimateChangedEvent.Trigger trigger : EstimateChangedEvent.Trigger.values()) {
            publishCommitted(f.projectId, f.estimateId, trigger);
            assertThat(fingerprint(f.projectId)).as("after %s", trigger).isEqualTo(before);
        }

        // Restore the estimate from the other offer snapshot: offers still unchanged.
        int restoreStatus = mockMvc.perform(post("/api/offers/" + other.getId() + "/restore")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn().getResponse().getStatus();
        assertThat(restoreStatus).isEqualTo(200);
        assertThat(fingerprint(f.projectId)).as("after restore").isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = OfferStatus.class, mode = Mode.EXCLUDE,
            names = {"DRAFT", "SENT", "COUNTERED", "CHANGES_REQUESTED", "APPROVED"})
    void terminalOffersAreUntouchedToo(OfferStatus status) {
        Fx f = emptyEstimateFixture("D3T-" + status);
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Closed");
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setStatus(status));
        List<String> before = fingerprint(f.projectId);

        addLine(f, new BigDecimal("5.00"), new BigDecimal("3.00"));
        publishCommitted(f.projectId, f.estimateId, EstimateChangedEvent.Trigger.ESTIMATE_SAVED);

        assertThat(fingerprint(f.projectId)).isEqualTo(before);
    }
}
