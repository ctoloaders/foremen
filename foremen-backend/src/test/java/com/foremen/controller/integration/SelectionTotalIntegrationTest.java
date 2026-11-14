package com.foremen.controller.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-01 task 6.3 - {@code POST /api/purchase-positions/selection-total}: counted/skipped
 * classification, VAT breakdown, mixed projects, size limits, audience and project scoping.
 *
 * <p>Repeatability: unique per-run data (names, ids from the sequence), rolled-back transaction.
 *
 * <p>Validates Requirements 6.1, 6.2, 6.3, 6.4, 6.5.
 */
class SelectionTotalIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String URL = POSITIONS + "/selection-total";

    private ResultActions total(RequestPostProcessor caller, String idsJson) throws Exception {
        var req = post(URL).header("Accept-Language", "pl").contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionIds\": " + idsJson + "}");
        return mockMvc.perform(caller == null ? req : req.with(caller));
    }

    private static String ids(PurchasePositionEntity... ps) {
        return java.util.Arrays.stream(ps).map(p -> String.valueOf(p.getId()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** AGREED + ACCEPTED position priced {@code salePrice} x {@code qty}, sale VAT {@code vat8} (default) or 23 %. */
    private PurchasePositionEntity counted(ProjectEntity project, String salePrice, String qty, boolean vat23Rate) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        p.setSalePrice(new BigDecimal(salePrice));
        p.setQuantity(new BigDecimal(qty));
        p.setSaleVatRate(vat23Rate ? vat23 : vat8);
        em.flush();
        return p;
    }

    @Test
    @DisplayName("counts only AGREED + ACCEPTED + priced positions; totals and VAT breakdown per rate")
    void countsAndSkips() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = counted(project, "100.00", "5.000", false);   // 500.00 + 40.00 = 540.00
        PurchasePositionEntity b = counted(project, "10.01", "2.000", true);     // 20.02 + 4.60 = 24.62
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity pkg = position(project, SourcingPath.PACKAGE, PositionStatus.ACCEPTED);
        PurchasePositionEntity noPrice = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        noPrice.setSalePrice(null);
        em.flush();
        em.clear();

        total(null, "[" + a.getId() + "," + b.getId() + "," + planned.getId() + "," + pkg.getId() + ","
                + noPrice.getId() + ",999999999]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.saleNet").value(520.02))
                .andExpect(jsonPath("$.vat").value(44.60))
                .andExpect(jsonPath("$.saleGross").value(564.62))
                .andExpect(jsonPath("$.vatBreakdown.length()").value(2))
                .andExpect(jsonPath("$.vatBreakdown[0].ratePercent").value(8))
                .andExpect(jsonPath("$.vatBreakdown[0].net").value(500.00))
                .andExpect(jsonPath("$.vatBreakdown[0].vat").value(40.00))
                .andExpect(jsonPath("$.vatBreakdown[0].gross").value(540.00))
                .andExpect(jsonPath("$.vatBreakdown[1].ratePercent").value(23))
                .andExpect(jsonPath("$.vatBreakdown[1].net").value(20.02))
                .andExpect(jsonPath("$.vatBreakdown[1].vat").value(4.60))
                .andExpect(jsonPath("$.vatBreakdown[1].gross").value(24.62))
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.mixedProjects").value(false))
                .andExpect(jsonPath("$.skipped.length()").value(4))
                .andExpect(jsonPath("$.skipped[?(@.positionId==" + planned.getId() + ")].reason")
                        .value("NOT_ACCEPTED"))
                .andExpect(jsonPath("$.skipped[?(@.positionId==" + pkg.getId() + ")].reason").value("NOT_AGREED"))
                .andExpect(jsonPath("$.skipped[?(@.positionId==" + noPrice.getId() + ")].reason")
                        .value("NO_SALE_PRICE"))
                .andExpect(jsonPath("$.skipped[?(@.positionId==999999999)].reason").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("duplicate ids are counted once")
    void duplicatesCountedOnce() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = counted(project, "100.00", "1.000", false);
        em.clear();

        total(null, "[" + a.getId() + "," + a.getId() + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.saleNet").value(100.00))
                .andExpect(jsonPath("$.saleGross").value(108.00));
    }

    @Test
    @DisplayName("positions of two projects -> mixedProjects true and projectId null")
    void mixedProjects() throws Exception {
        PurchasePositionEntity a = counted(project(ProjectStatus.ACTIVE), "100.00", "1.000", false);
        PurchasePositionEntity b = counted(project(ProjectStatus.ACTIVE), "50.00", "1.000", false);
        em.clear();

        total(null, ids(a, b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.mixedProjects").value(true))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.saleNet").value(150.00));
    }

    @Test
    @DisplayName("only skipped positions -> count 0, zero totals, no project")
    void nothingCounted() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.clear();

        total(null, ids(planned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.saleNet").value(0))
                .andExpect(jsonPath("$.saleGross").value(0))
                .andExpect(jsonPath("$.vatBreakdown.length()").value(0))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.mixedProjects").value(false))
                .andExpect(jsonPath("$.skipped.length()").value(1));
    }

    @Test
    @DisplayName("empty list, missing list -> 400; 501 ids -> 400; 500 ids -> 200")
    void sizeLimits() throws Exception {
        total(null, "[]").andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        total(null, range(501)).andExpect(status().isBadRequest());
        total(null, range(500)).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(500));
    }

    private static String range(int n) {
        return LongStream.rangeClosed(900_000_000L, 900_000_000L + n - 1).mapToObj(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("FOREMAN and CLIENT -> 403; MANAGER / BUYER members are counted")
    void audienceMatrix() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity a = counted(project, "100.00", "1.000", false);
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity client = memberUser("CLIENT", project);
        UserEntity manager = memberUser("MANAGER", project);
        UserEntity buyer = memberUser("BUYER", project);
        em.clear();

        total(as(foreman, "FOREMAN"), ids(a)).andExpect(status().isForbidden());
        total(as(client, "CLIENT"), ids(a)).andExpect(status().isForbidden());
        total(as(manager, "MANAGER"), ids(a)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        total(as(buyer, "BUYER"), ids(a)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("a non-member MANAGER sees positions of foreign projects as NOT_FOUND")
    void foreignProjectIsNotFound() throws Exception {
        ProjectEntity mine = project(ProjectStatus.ACTIVE);
        ProjectEntity foreign = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", mine);
        PurchasePositionEntity own = counted(mine, "100.00", "1.000", false);
        PurchasePositionEntity other = counted(foreign, "100.00", "1.000", false);
        em.clear();

        total(as(manager, "MANAGER"), ids(own, other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.projectId").value(mine.getId()))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].positionId").value(other.getId()))
                .andExpect(jsonPath("$.skipped[0].reason").value("NOT_FOUND"));
    }
}
