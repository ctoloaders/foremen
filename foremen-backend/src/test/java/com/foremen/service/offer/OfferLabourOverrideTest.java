package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;
import com.foremen.service.image.ImageStorage;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FOR-05-07 bug-report Round 4 (tasks 1-2): the offer prices labour on the EFFECTIVE (override) net,
 * and the executor read model carries the staff-only original-vs-effective comparison ONLY for the
 * explicit {ADMIN, MANAGER, ESTIMATOR, FINANCIER} allow-list; the CLIENT payload never contains the
 * original labour.
 */
class OfferLabourOverrideTest {

    private final ClientOfferReadModelAssembler assembler = new ClientOfferReadModelAssembler(
            new OfferVisibilityResolver(), mock(EstimateLineRoomMaterialDao.class), mock(ImageStorage.class));

    // ---- helper -------------------------------------------------------------------------------

    @Test
    @DisplayName("effectiveValueNet: override wins; null override falls back to unitPrice; HALF_UP scale 2")
    void effectiveValueNetHelper() {
        EstimateLineEntity noOverride = line(1L, "100.00", null, "3");
        assertThat(noOverride.effectiveValueNet()).isEqualByComparingTo("300.00");

        EstimateLineEntity withOverride = line(2L, "100.00", "85.00", "3");
        assertThat(withOverride.effectiveValueNet()).isEqualByComparingTo("255.00");
        assertThat(withOverride.originalValueNet()).isEqualByComparingTo("300.00");

        EstimateLineEntity rounding = line(3L, "10.00", "3.335", "1");
        assertThat(rounding.effectiveValueNet()).isEqualByComparingTo("3.34");
        assertThat(rounding.effectiveValueNet().scale()).isEqualTo(2);

        EstimateLineEntity nulls = new EstimateLineEntity();
        nulls.setQuantity(null);
        assertThat(nulls.effectiveValueNet()).isEqualByComparingTo("0.00");
    }

    // ---- offer totals ---------------------------------------------------------------------------

    @Test
    @DisplayName("offer totals use the override net; without override they equal the original")
    void offerTotalsUseOverride() {
        OfferService service = offerService();

        OfferEntity plain = offer(line(1L, "100.00", null, "2"), line(2L, "50.00", null, "1"));
        service.recomputeTotals(plain);
        assertThat(plain.getTotalNet()).isEqualByComparingTo("250.00");

        // line 1 override 80 => 160 + 50 = 210 (original-derived valueNet would still give 250).
        OfferEntity overridden = offer(line(1L, "100.00", "80.00", "2"), line(2L, "50.00", null, "1"));
        service.recomputeTotals(overridden);
        assertThat(overridden.getTotalNet()).isEqualByComparingTo("210.00");
        assertThat(overridden.getTotalGross()).isEqualByComparingTo("210.00");
    }

    // ---- role gate / comparison block ----------------------------------------------------------

    @Test
    @DisplayName("canSeeLabourComparison: allow-list ADMIN/MANAGER/ESTIMATOR/FINANCIER only")
    void allowList() {
        for (String role : List.of("ADMIN", "MANAGER", "ESTIMATOR", "FINANCIER")) {
            assertThat(ClientOfferReadModelAssembler.canSeeLabourComparison(role)).as(role).isTrue();
        }
        for (String role : new String[] {"FOREMAN", "CLIENT", "WORKER", "SALESMAN", "", null}) {
            assertThat(ClientOfferReadModelAssembler.canSeeLabourComparison(role)).as(String.valueOf(role)).isFalse();
        }
    }

    @Test
    @DisplayName("executor model: block present for allowed roles, null (omitted from JSON) otherwise")
    void executorBlockByRole() throws Exception {
        OfferEntity offer = offer(line(1L, "100.00", "80.00", "2"), line(2L, "50.00", null, "1"));

        for (String role : List.of("ADMIN", "MANAGER", "ESTIMATOR", "FINANCIER")) {
            ExecutorOfferReadModel model = assembler.toExecutorReadModel(
                    offer, true, ClientOfferReadModelAssembler.canSeeLabourComparison(role));
            assertThat(model.labourComparison()).as(role).isNotNull();
            assertThat(model.labourComparison().originalLabourNet()).isEqualByComparingTo("250.00");
            assertThat(model.labourComparison().effectiveLabourNet()).isEqualByComparingTo("210.00");
        }

        ExecutorOfferReadModel foreman = assembler.toExecutorReadModel(
                offer, true, ClientOfferReadModelAssembler.canSeeLabourComparison("FOREMAN"));
        assertThat(foreman.labourComparison()).isNull();
        String foremanJson = mapper().writeValueAsString(foreman);
        assertThat(foremanJson).doesNotContain("labourComparison").doesNotContain("originalLabourNet");

        String adminJson = mapper().writeValueAsString(assembler.toExecutorReadModel(offer, true, true));
        assertThat(adminJson).contains("\"labourComparison\"").contains("originalLabourNet");
    }

    @Test
    @DisplayName("CLIENT payload JSON contains no original-labour field")
    void clientPayloadHasNoOriginalLabour() throws Exception {
        OfferEntity offer = offer(line(1L, "100.00", "80.00", "2"));
        String json = mapper().writeValueAsString(assembler.toClientReadModel(offer));
        assertThat(json)
                .doesNotContain("labourComparison")
                .doesNotContain("originalLabour")
                .doesNotContain("unitPrice")
                .doesNotContain("valueNet");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private static OfferService offerService() {
        return new OfferService(
                mock(OfferDao.class), mock(EstimateDao.class), mock(EstimateLineRoomMaterialDao.class),
                mock(OfferPackageDao.class), mock(UserDao.class),
                mock(com.foremen.service.model.mapper.OfferServiceMapper.class),
                mock(com.foremen.service.ProjectAccessCache.class), mock(AuditLogDao.class),
                mock(EntityManager.class), new OfferStatusMachine(), new OfferTotalsCalculator(),
                new DiscountResolver(), mock(com.foremen.service.estimate.DraftGateGuard.class),
                mock(com.foremen.service.estimate.EstimateAssignmentService.class),
                mock(ApplicationEventPublisher.class), mock(ClientOfferReadModelAssembler.class));
    }

    private static OfferEntity offer(EstimateLineEntity... lines) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(9L);
        estimate.setLines(List.of(lines));
        OfferEntity offer = new OfferEntity();
        offer.setId(700L);
        offer.setStatus(OfferStatus.DRAFT);
        offer.setEstimate(estimate);
        return offer;
    }

    private static EstimateLineEntity line(Long id, String unitPrice, String override, String qty) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(id);
        line.setUnitPrice(new BigDecimal(unitPrice));
        line.setUnitPriceOverride(override == null ? null : new BigDecimal(override));
        line.setQuantity(new BigDecimal(qty));
        // The persisted valueNet is derived from the ORIGINAL and must be ignored by the offer.
        line.setValueNet(new BigDecimal(unitPrice).multiply(new BigDecimal(qty)));
        return line;
    }
}
