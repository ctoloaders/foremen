package com.foremen.service.offer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

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

import com.foremen.dao.model.OfferEntity;
import com.foremen.service.OfferService;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07b task 4.1: {@code GET /api/offers/{id}/pdf} through MockMvc. Staff 200 application/pdf,
 * CLIENT 404 for a DRAFT offer and 200 once SENT, legacy offer 404 error.offer.snapshot.missing.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferPdfEndpointIntegrationTest extends OfferExplorationSupport {

    @Autowired
    private OfferService offerService;
    @Autowired
    private MockMvc mockMvc;

    private MvcResult getPdf(Long offerId, org.springframework.test.web.servlet.request.RequestPostProcessor who)
            throws Exception {
        return mockMvc.perform(get("/api/offers/" + offerId + "/pdf").with(who)).andReturn();
    }

    @Test
    @DisplayName("staff gets 200 application/pdf with a PDF body and an attachment file name")
    void staffGetsPdf() throws Exception {
        Fx f = emptyEstimateFixture("P1");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Staff pdf");

        MvcResult result = getPdf(offer.getId(), user("admin-" + runId).roles("ADMIN"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(result.getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("staff-pdf-" + offer.getId() + ".pdf");
    }

    @Test
    @DisplayName("CLIENT gets 404 for a DRAFT offer and a PDF once the offer is SENT")
    void clientSeesPdfOnlyAfterSend() throws Exception {
        Fx f = emptyEstimateFixture("P2");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Client pdf");
        createClientUser();

        MvcResult draft = getPdf(offer.getId(), user("client-" + runId).roles("CLIENT"));
        assertThat(draft.getResponse().getStatus()).isEqualTo(404);

        authenticateAdmin();
        offerService.send(offer.getId());
        MvcResult sent = getPdf(offer.getId(), user("client-" + runId).roles("CLIENT"));
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        assertThat(sent.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(sent.getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("a legacy offer without a snapshot answers 404 error.offer.snapshot.missing")
    void legacyOfferHasNoPdf() throws Exception {
        Fx f = emptyEstimateFixture("P3");
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Legacy pdf");
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setSnapshot(null));

        MvcResult result = getPdf(offer.getId(), user("admin-" + runId).roles("ADMIN"));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("error.offer.snapshot.missing");
    }
}
