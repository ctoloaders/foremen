package com.foremen.controller.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.PurchaseRequestOverdue;

/**
 * FOR-11-00 task 11.6 - boundary and shape cases of {@code GET /api/purchase-positions/upcoming} that
 * {@link PlannedDeliveryAndUpcomingIntegrationTest} does not pin: the exact window edge, "today" is not
 * overdue, yesterday is, every planned-delivery stage, the project-name tiebreak, the item fields
 * (actual material preferred, locale unit name, request id) and the 1 / 60 day bounds.
 *
 * <p>The edge dates are derived from the same clock the service uses, so the test is stable across the
 * UTC / Warsaw day boundary. Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 23.1, 23.2, 23.3, 23.4.
 */
class UpcomingDeliveriesEdgeIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String UPCOMING = POSITIONS + "/upcoming";

    private final Clock clock = new Clock();

    /** Today on the clock the service uses (Europe/Warsaw). */
    private static final class Clock {
        LocalDate today() {
            return LocalDate.now(PurchaseRequestOverdue.ZONE);
        }
    }

    private PurchasePositionEntity planned(ProjectEntity project, PositionStatus status, LocalDate date) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        p.setPlannedDeliveryDate(date);
        em.flush();
        return p;
    }

    private String ids(ProjectEntity project) {
        return String.valueOf(project.getId());
    }

    @Test
    @DisplayName("window edge - today + days is included, today + days + 1 is not")
    void windowEdge() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate today = clock.today();
        PurchasePositionEntity onEdge = planned(project, PositionStatus.ORDERED, today.plusDays(7));
        planned(project, PositionStatus.ORDERED, today.plusDays(8));
        em.clear();

        mockMvc.perform(get(UPCOMING).param("days", "7").param("projectId", ids(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].positionId").value(onEdge.getId()))
                .andExpect(jsonPath("$[0].overdue").value(false));
    }

    @Test
    @DisplayName("overdue flag - today is not overdue, yesterday is; the bounds days=1 and days=60 are accepted")
    void overdueBoundaryAndDayBounds() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate today = clock.today();
        PurchasePositionEntity yesterday = planned(project, PositionStatus.IN_DELIVERY, today.minusDays(1));
        PurchasePositionEntity todays = planned(project, PositionStatus.IN_DELIVERY, today);
        em.clear();

        mockMvc.perform(get(UPCOMING).param("days", "1").param("projectId", ids(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].positionId").value(yesterday.getId()))
                .andExpect(jsonPath("$[0].overdue").value(true))
                .andExpect(jsonPath("$[1].positionId").value(todays.getId()))
                .andExpect(jsonPath("$[1].overdue").value(false));
        mockMvc.perform(get(UPCOMING).param("days", "60").param("projectId", ids(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("stage set - WITH_BUYER, ORDERED, SUPPLIER_PAID, IN_DELIVERY and PARTIALLY_RECEIVED are listed; PLANNED and PROPOSED are not")
    void everyPlannedDeliveryStage() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate date = clock.today().plusDays(3);
        for (PositionStatus s : new PositionStatus[] {PositionStatus.WITH_BUYER, PositionStatus.ORDERED,
                PositionStatus.SUPPLIER_PAID, PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED}) {
            planned(project, s, date);
        }
        planned(project, PositionStatus.PLANNED, date);
        planned(project, PositionStatus.PROPOSED, date);
        em.clear();

        mockMvc.perform(get(UPCOMING).param("projectId", ids(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @DisplayName("ordering - the same date falls back to the project name")
    void sameDate_sortsByProjectName() throws Exception {
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        b.setName("PC upcoming B " + unique());
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        a.setName("PC upcoming A " + unique());
        em.flush();
        LocalDate date = clock.today().plusDays(4);
        PurchasePositionEntity inB = planned(b, PositionStatus.ORDERED, date);
        PurchasePositionEntity inA = planned(a, PositionStatus.ORDERED, date);
        PurchasePositionEntity earlier = planned(b, PositionStatus.ORDERED, date.minusDays(1));
        em.clear();

        mockMvc.perform(get(UPCOMING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.positionId == " + earlier.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.positionId == " + inA.getId() + ")].projectName")
                        .value(org.hamcrest.Matchers.hasItem(a.getName())))
                .andExpect(jsonPath("$[?(@.positionId == " + inB.getId() + ")].projectName")
                        .value(org.hamcrest.Matchers.hasItem(b.getName())));
        // Order: the earlier date first, then A before B on the shared date.
        var body = mockMvc.perform(get(UPCOMING)).andReturn().getResponse().getContentAsString();
        int iEarlier = body.indexOf("\"positionId\":" + earlier.getId());
        int iA = body.indexOf("\"positionId\":" + inA.getId());
        int iB = body.indexOf("\"positionId\":" + inB.getId());
        org.assertj.core.api.Assertions.assertThat(iEarlier).isGreaterThanOrEqualTo(0).isLessThan(iA);
        org.assertj.core.api.Assertions.assertThat(iA).isLessThan(iB);
    }

    @Test
    @DisplayName("item fields - actual material label wins over the planned one, unit name follows the locale, requestId is set")
    void itemFields() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity withActual = planned(project, PositionStatus.ORDERED, clock.today().plusDays(2));
        PurchasePositionEntity plannedOnly = planned(project, PositionStatus.ORDERED, clock.today().plusDays(3));

        FinishingMaterialEntity actual = material(null);
        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode("pc_prod_" + unique());
        producer.setNameRU("Производитель");
        producer.setNamePL("Producent");
        producer.setActive(true);
        em.persist(producer);
        actual.setProducer(producer);
        withActual.setActualMaterial(actual);
        PurchaseRequestEntity request = request(project);
        attach(withActual, request);
        em.flush();
        em.clear();

        String plannedModel = em.find(PurchasePositionEntity.class, plannedOnly.getId())
                .getPlannedMaterial().getModel();
        em.clear();

        mockMvc.perform(get(UPCOMING).header("Accept-Language", "pl").param("projectId", ids(project)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].positionId").value(withActual.getId()))
                .andExpect(jsonPath("$[0].materialModel").value(actual.getModel()))
                .andExpect(jsonPath("$[0].producer").value("Producent"))
                .andExpect(jsonPath("$[0].unitName").value("m2"))
                .andExpect(jsonPath("$[0].requestId").value(request.getId()))
                .andExpect(jsonPath("$[1].materialModel").value(plannedModel))
                .andExpect(jsonPath("$[1].requestId").doesNotExist());
        mockMvc.perform(get(UPCOMING).header("Accept-Language", "ru").param("projectId", ids(project)))
                .andExpect(jsonPath("$[0].unitName").value("м2"))
                .andExpect(jsonPath("$[0].producer").value("Производитель"));
    }
}
