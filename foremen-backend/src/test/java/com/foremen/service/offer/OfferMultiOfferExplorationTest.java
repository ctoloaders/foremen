package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07b task 1 (bugfix.md 1.4, 1.5, 1.6): several non-terminal offers per project, offer PDF and
 * restore endpoints. EXPECTED TO FAIL on unfixed code. The endpoints are called by path through
 * MockMvc, so the class compiles without the new Java types.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferMultiOfferExplorationTest extends OfferExplorationSupport {

    @Autowired
    private OfferService offerService;
    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1.5: preparing twice for one project succeeds and leaves two non-terminal offers")
    void secondPrepareSucceeds() {
        Fx f = emptyEstimateFixture("M");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        offerService.prepareOffer(f.projectId, "First");

        // Counterexample on unfixed code: ForemenApiException 409 error.offer.active.exists.
        OfferEntity second = offerService.prepareOffer(f.projectId, "Second");

        assertThat(second.getId()).isNotNull();
        List<OfferEntity> all = tx().execute(s -> entityManager
                .createQuery("select o from OfferEntity o where o.project.id = :p", OfferEntity.class)
                .setParameter("p", f.projectId).getResultList());
        assertThat(all).filteredOn(o -> o.getStatus() != OfferStatus.APPROVED
                && o.getStatus() != OfferStatus.REJECTED && o.getStatus() != OfferStatus.WITHDRAWN)
                .hasSize(2);
    }

    @Test
    @DisplayName("1.4: GET /api/offers/{id}/pdf answers 200 application/pdf for an offer with a snapshot")
    void pdfEndpointExists() throws Exception {
        Fx f = emptyEstimateFixture("P");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Pdf");

        // Counterexample on unfixed code: 404 (no handler for GET /api/offers/{id}/pdf).
        MvcResult result = mockMvc.perform(get("/api/offers/" + offer.getId() + "/pdf")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET pdf status").isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("1.6: POST /api/offers/{id}/restore answers 200 for an offer with a snapshot")
    void restoreEndpointExists() throws Exception {
        Fx f = emptyEstimateFixture("S");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Restore");

        // Counterexample on unfixed code: 404 (no handler for POST /api/offers/{id}/restore).
        MvcResult result = mockMvc.perform(post("/api/offers/" + offer.getId() + "/restore")
                .with(user("admin-" + runId).roles("ADMIN"))).andReturn();
        assertThat(result.getResponse().getStatus()).as("POST restore status").isEqualTo(200);
    }
}
