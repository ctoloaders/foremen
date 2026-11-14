package com.foremen.controller.integration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 11.3 - project scoping and per-audience price visibility of
 * {@code /api/purchase-positions}, including the Money_Field filter / sort rejection.
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 9.1, 9.2, 9.3, 10.*, 18.1.
 */
class PurchasePositionVisibilityScopingIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String PL_FORBIDDEN_FIELD = "Brak uprawnień do filtrowania po tym polu.";
    private static final String[] MONEY = {
            "plannedPurchasePrice", "actualPurchasePrice", "purchaseVatRateId",
            "salePrice", "saleVatRateId", "deliveryCostShare"};

    private ResultActions getById(PurchasePositionEntity p, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get(POSITIONS + "/" + p.getId()).with(auth));
    }

    private ResultActions list(RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(get(POSITIONS).param("page", "0").param("size", "200").with(auth));
    }

    private static int id(PurchasePositionEntity p) {
        return Math.toIntExact(p.getId());
    }

    // --- scoping ---

    @Test
    @DisplayName("a project member sees only own project's positions; by-id of another project -> 404")
    void memberSeesOnlyOwnProject() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity inA = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity inB = position(b, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity foreman = memberUser("FOREMAN", a);
        RequestPostProcessor auth = as(foreman, "FOREMAN");

        list(auth).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(inA))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(inB)))));
        getById(inA, auth).andExpect(status().isOk());
        getById(inB, auth).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a non-member sees nothing and gets 404 by id")
    void nonMemberSeesNothing() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity inA = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity outsider = user("FOREMAN");
        RequestPostProcessor auth = as(outsider, "FOREMAN");

        list(auth).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(inA)))));
        getById(inA, auth).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a BUYER without any membership sees the positions of all projects")
    void buyerSeesAllProjects() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity inA = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity inB = position(b, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        UserEntity buyer = user("BUYER");
        RequestPostProcessor auth = as(buyer, "BUYER");

        list(auth).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(inA))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(inB))));
        getById(inA, auth).andExpect(status().isOk());
        getById(inB, auth).andExpect(status().isOk());
    }

    // --- price visibility ---

    @Test
    @DisplayName("STAFF_FULL (ADMIN, member MANAGER, BUYER) sees every Money_Field")
    void staffSeesAllMoney() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity manager = memberUser("MANAGER", a);
        UserEntity buyer = user("BUYER");

        for (RequestPostProcessor auth : new RequestPostProcessor[] {
                as(manager, "MANAGER"), as(buyer, "BUYER")}) {
            getById(pos, auth).andExpect(status().isOk())
                    .andExpect(jsonPath("$.salePrice").value(100.0))
                    .andExpect(jsonPath("$.plannedPurchasePrice").value(80.0))
                    .andExpect(jsonPath("$.actualPurchasePrice").value(85.0))
                    .andExpect(jsonPath("$.deliveryCostShare").value(7.0))
                    .andExpect(jsonPath("$.purchaseVatRateId").value(vat23.getId()))
                    .andExpect(jsonPath("$.saleVatRateId").value(vat8.getId()));
        }
        mockMvc.perform(get(POSITIONS + "/" + pos.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value(100.0));
    }

    @Test
    @DisplayName("SITE (FOREMAN) sees no money in detail or list")
    void foremanSeesNoMoney() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(a, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        UserEntity foreman = memberUser("FOREMAN", a);
        RequestPostProcessor auth = as(foreman, "FOREMAN");

        ResultActions detail = getById(pos, auth).andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(5.0));
        for (String f : MONEY) {
            detail.andExpect(jsonPath("$." + f).value(nullValue()));
        }
        ResultActions page = list(auth).andExpect(status().isOk());
        for (String f : MONEY) {
            page.andExpect(jsonPath("$.content[?(@.id == " + pos.getId() + ")]." + f).value(hasItem(nullValue())));
        }
    }

    @Test
    @DisplayName("CLIENT: sale price only, only for AGREED positions from ACCEPTED on; never purchase-side fields")
    void clientSeesSalePriceOnlyForAcceptedAgreed() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity planned = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity accepted = position(a, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity pkg = position(a, SourcingPath.PACKAGE, PositionStatus.WITH_BUYER);
        PurchasePositionEntity own = position(a, SourcingPath.CLIENT_OWN, PositionStatus.IN_DELIVERY);
        UserEntity client = memberUser("CLIENT", a);
        RequestPostProcessor auth = as(client, "CLIENT");

        getById(accepted, auth).andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value(100.0))
                .andExpect(jsonPath("$.plannedPurchasePrice").value(nullValue()))
                .andExpect(jsonPath("$.actualPurchasePrice").value(nullValue()))
                .andExpect(jsonPath("$.purchaseVatRateId").value(nullValue()))
                .andExpect(jsonPath("$.saleVatRateId").value(nullValue()))
                .andExpect(jsonPath("$.deliveryCostShare").value(nullValue()));
        for (PurchasePositionEntity hidden : new PurchasePositionEntity[] {planned, pkg, own}) {
            ResultActions r = getById(hidden, auth).andExpect(status().isOk());
            for (String f : MONEY) {
                r.andExpect(jsonPath("$." + f).value(nullValue()));
            }
        }
    }

    // --- Money_Field filter / sort ---

    @Test
    @DisplayName("non-staff filter or sort by a Money_Field -> 400 field.forbidden")
    void moneyFilterAndSort_rejectedForNonStaff() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity foreman = memberUser("FOREMAN", a);
        UserEntity client = memberUser("CLIENT", a);

        for (RequestPostProcessor auth : new RequestPostProcessor[] {as(foreman, "FOREMAN"), as(client, "CLIENT")}) {
            mockMvc.perform(get(POSITIONS).header("Accept-Language", "pl").param("query", "salePrice==100").with(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_FORBIDDEN_FIELD));
            mockMvc.perform(get(POSITIONS).header("Accept-Language", "pl").param("sort", "plannedPurchasePrice,asc")
                            .with(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_FORBIDDEN_FIELD));
        }
        // A non-money filter / sort is fine for the same caller.
        mockMvc.perform(get(POSITIONS).param("sort", "needByDate,asc").param("query", "status==PLANNED")
                        .with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("staff may filter and sort by a Money_Field")
    void moneyFilterAndSort_allowedForStaff() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity buyer = user("BUYER");

        mockMvc.perform(get(POSITIONS).param("query", "salePrice==100").param("sort", "salePrice,desc")
                        .param("size", "200").with(as(buyer, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(pos))));
        mockMvc.perform(get(POSITIONS).param("query", "salePrice==100").param("size", "200"))
                .andExpect(status().isOk());
    }
}
