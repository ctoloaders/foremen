package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VariantReason;
import com.foremen.dao.model.WorkCategoryEntity;

/**
 * FOR-11-00 task 11.3 - create / update / delete rules of {@code /api/purchase-positions}.
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction (see {@link ProcurementIntegrationTestBase}).
 *
 * <p>Validates Requirements 7.*, 9.4, 9.5, 9.6, 18.1.
 */
class PurchasePositionWriteRulesIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String PL_NOT_ACTIVE = "Projekt nie jest w realizacji.";
    private static final String PL_QUANTITY = "Ilość musi być większa od zera.";
    private static final String PL_PRICE = "Cena nie może być ujemna.";
    private static final String PL_MISMATCH = "Materiał należy do innego projektu.";
    private static final String PL_READ_ONLY = "To pole jest zarządzane przez system.";
    private static final String PL_DELETE = "Pozycję można usunąć tylko w statusie „Nowe” lub „Anulowane”.";

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(POSITIONS).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(Long id, String body) throws Exception {
        return mockMvc.perform(put(POSITIONS + "/" + id).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --- create ---

    @Test
    @DisplayName("POST - ACTIVE project: PLANNED position, VAT defaults 23/8, unit defaults from material")
    void create_activeProject_appliesDefaults() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = material(null);

        create(createPositionJson(project.getId(), m.getId(), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.kind").value("FINISHING"))
                .andExpect(jsonPath("$.unitId").value(unit.getId()))
                .andExpect(jsonPath("$.purchaseVatRateId").value(vat23.getId()))
                .andExpect(jsonPath("$.saleVatRateId").value(vat8.getId()))
                .andExpect(jsonPath("$.variantCount").value(0));
    }

    @Test
    @DisplayName("POST - explicit VAT rates are kept")
    void create_explicitVat_isKept() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = material(null);

        create(createPositionJson(project.getId(), m.getId(),
                "\"purchaseVatRateId\": " + vat8.getId() + ", \"saleVatRateId\": " + vat23.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseVatRateId").value(vat8.getId()))
                .andExpect(jsonPath("$.saleVatRateId").value(vat23.getId()));
    }

    @Test
    @DisplayName("POST - non-ACTIVE project -> 409 project.notActive")
    void create_nonActiveProject_returns409() throws Exception {
        ProjectEntity draft = project(ProjectStatus.DRAFT);
        create(createPositionJson(draft.getId(), material(null).getId(), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_NOT_ACTIVE));
    }

    @Test
    @DisplayName("POST - kind other than FINISHING -> 400")
    void create_nonFinishingKind_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        String body = createPositionJson(project.getId(), material(null).getId(), "")
                .replace("\"FINISHING\"", "\"FURNITURE\"");
        create(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - quantity <= 0 -> 400 quantity.invalid")
    void create_nonPositiveQuantity_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long materialId = material(null).getId();
        for (String q : new String[] {"0", "-2"}) {
            create(createPositionJson(project.getId(), materialId, "").replace("\"quantity\": 3", "\"quantity\": " + q))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_QUANTITY));
        }
    }

    @Test
    @DisplayName("POST - negative price -> 400 price.invalid; zero price is accepted")
    void create_negativePrice_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long materialId = material(null).getId();
        create(createPositionJson(project.getId(), materialId, "\"salePrice\": -1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_PRICE));
        create(createPositionJson(project.getId(), materialId, "\"plannedPurchasePrice\": -0.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_PRICE));
        create(createPositionJson(project.getId(), materialId, "\"salePrice\": 0, \"plannedPurchasePrice\": 0"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST - Project_Material of another project -> 400 material.projectMismatch; own project's is accepted")
    void create_materialOfOtherProject_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);

        create(createPositionJson(project.getId(), material(other).getId(), ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_MISMATCH));
        create(createPositionJson(project.getId(), material(project).getId(), ""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST - a non-member MANAGER cannot create in a project (404); a member can")
    void create_scopedToMembership() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long materialId = material(null).getId();
        UserEntity outsider = user("MANAGER");
        UserEntity member = memberUser("MANAGER", project);

        mockMvc.perform(post(POSITIONS).with(as(outsider, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPositionJson(project.getId(), materialId, "")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(POSITIONS).with(as(member, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPositionJson(project.getId(), materialId, "")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST - FOREMAN has no CREATE grant -> 403")
    void create_foreman_returns403() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity foreman = memberUser("FOREMAN", project);
        mockMvc.perform(post(POSITIONS).with(as(foreman, "FOREMAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPositionJson(project.getId(), material(null).getId(), "")))
                .andExpect(status().isForbidden());
    }

    // --- update: system-owned fields ---

    @Test
    @DisplayName("PUT - supplying a system-owned field -> 400 field.readOnly")
    void update_systemOwnedFields_return400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        for (String field : new String[] {
                "\"status\": \"ACCEPTED\"", "\"orderedQuantity\": 1", "\"acceptedQuantity\": 1",
                "\"deliveryCostShare\": 1", "\"plannedDeliveryDate\": \"2030-01-01\""}) {
            update(pos.getId(), "{" + field + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_READ_ONLY));
        }
    }

    @Test
    @DisplayName("PUT - changing projectId or kind -> 400 field.readOnly")
    void update_projectAndKind_return400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        update(pos.getId(), "{\"projectId\": " + other.getId() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_READ_ONLY));
        update(pos.getId(), "{\"kind\": \"FURNITURE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_READ_ONLY));
    }

    // --- update: stage locks ---

    @Test
    @DisplayName("PUT - at PLANNED the planned item, path and quantity may change")
    void update_planningStage_allowsStructuralChanges() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long newMaterial = material(null).getId();

        update(pos.getId(), "{\"quantity\": 9, \"sourcingPath\": \"PACKAGE\", \"plannedMaterialId\": "
                + newMaterial + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(9))
                .andExpect(jsonPath("$.sourcingPath").value("PACKAGE"))
                .andExpect(jsonPath("$.plannedMaterialId").value(newMaterial));
    }

    @Test
    @DisplayName("PUT - from ACCEPTED on, quantity / path / planned item -> 409 field.readOnly; notes still editable")
    void update_afterPlanning_locksStructuralFields() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);

        update(pos.getId(), "{\"quantity\": 9}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_READ_ONLY));
        update(pos.getId(), "{\"sourcingPath\": \"PACKAGE\"}").andExpect(status().isConflict());
        update(pos.getId(), "{\"plannedMaterialId\": " + material(null).getId() + "}")
                .andExpect(status().isConflict());

        update(pos.getId(), "{\"quantity\": 5, \"notes\": \"edited\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("edited"));
    }

    @Test
    @DisplayName("PUT - actual item / actual price are locked before WITH_BUYER and open from it on")
    void update_actualFields_lockedBeforeBuyer() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long actualMaterial = material(null).getId();
        PurchasePositionEntity early = position(project, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);
        PurchasePositionEntity withBuyer = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);

        update(early.getId(), "{\"actualMaterialId\": " + actualMaterial + "}").andExpect(status().isConflict());
        update(early.getId(), "{\"actualPurchasePrice\": 12}").andExpect(status().isConflict());

        update(withBuyer.getId(), "{\"actualMaterialId\": " + actualMaterial + ", \"actualPurchasePrice\": 12}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualMaterialId").value(actualMaterial))
                .andExpect(jsonPath("$.actualPurchasePrice").value(12));
    }

    @Test
    @DisplayName("PUT - with a variant history the planned item and agreed prices are locked (409)")
    void update_withVariantHistory_locksItemAndPrices() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionVariantEntity v = new PurchasePositionVariantEntity();
        v.setPosition(pos);
        v.setMaterial(material(project));
        v.setReason(VariantReason.ANALOG);
        em.persist(v);
        em.flush();

        update(pos.getId(), "{\"plannedMaterialId\": " + material(null).getId() + "}")
                .andExpect(status().isConflict());
        update(pos.getId(), "{\"salePrice\": 555}").andExpect(status().isConflict());
        update(pos.getId(), "{\"plannedPurchasePrice\": 1}").andExpect(status().isConflict());
        // Unchanged value and other fields stay editable.
        update(pos.getId(), "{\"salePrice\": 100, \"notes\": \"ok\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT - validations on update: quantity <= 0 -> 400, negative price -> 400")
    void update_invalidValues_return400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        update(pos.getId(), "{\"quantity\": 0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_QUANTITY));
        update(pos.getId(), "{\"needByDate\": \"2031-02-03\", \"salePrice\": -5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_PRICE));
    }

    // --- FOR-11-01 R5.4: manual work-category override ---

    private WorkCategoryEntity workCategory(boolean active) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        String u = unique();
        c.setCode("WC-" + u);
        c.setOrderNo(9000);
        c.setNameRU("Категория " + u);
        c.setNamePL("Kategoria " + u);
        c.setActive(active);
        em.persist(c);
        em.flush();
        return c;
    }

    @Test
    @DisplayName("POST/PUT - workCategoryId is stored, returned and may be cleared via DELETE property (before ORDERED)")
    void workCategory_setAndReset() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        WorkCategoryEntity cat = workCategory(true);
        create(createPositionJson(project.getId(), material(null).getId(), "\"workCategoryId\": " + cat.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workCategoryId").value(cat.getId()));
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        update(pos.getId(), "{\"workCategoryId\": " + cat.getId() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workCategoryId").value(cat.getId()));
        mockMvc.perform(delete(POSITIONS + "/" + pos.getId() + "/property").param("properties", "workCategory"))
                .andExpect(status().is2xxSuccessful());
        em.flush();
        em.clear();
        assertThat(em.find(PurchasePositionEntity.class, pos.getId()).getWorkCategory()).isNull();
    }

    @Test
    @DisplayName("PUT/POST - unknown or inactive work category -> 400")
    void workCategory_invalid_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        update(pos.getId(), "{\"workCategoryId\": 987654321}").andExpect(status().isBadRequest());
        update(pos.getId(), "{\"workCategoryId\": " + workCategory(false).getId() + "}")
                .andExpect(status().isBadRequest());
        create(createPositionJson(project.getId(), material(null).getId(), "\"workCategoryId\": 987654321"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT - from ORDERED on the work-category override is locked (409); an unchanged value is accepted")
    void workCategory_lockedFromOrdered() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        WorkCategoryEntity cat = workCategory(true);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        update(pos.getId(), "{\"workCategoryId\": " + cat.getId() + "}").andExpect(status().isConflict());
        pos.setWorkCategory(cat);
        em.flush();
        update(pos.getId(), "{\"workCategoryId\": " + cat.getId() + ", \"notes\": \"n\"}")
                .andExpect(status().isOk());
    }

    // --- delete ---

    @Test
    @DisplayName("DELETE - ADMIN may delete PLANNED and CANCELLED positions")
    void delete_plannedAndCancelled_succeeds() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity cancelled = position(project, SourcingPath.AGREED, PositionStatus.CANCELLED);

        mockMvc.perform(delete(POSITIONS + "/" + planned.getId())).andExpect(status().is2xxSuccessful());
        mockMvc.perform(delete(POSITIONS + "/" + cancelled.getId())).andExpect(status().is2xxSuccessful());
        em.flush();
        em.clear();
        assertThat(em.find(PurchasePositionEntity.class, planned.getId())).isNull();
        assertThat(em.find(PurchasePositionEntity.class, cancelled.getId())).isNull();
    }

    @Test
    @DisplayName("DELETE - any other stage -> 409 delete.notAllowed")
    void delete_otherStage_returns409() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        for (PositionStatus s : new PositionStatus[] {
                PositionStatus.PROPOSED, PositionStatus.ACCEPTED, PositionStatus.WITH_BUYER, PositionStatus.RECEIVED}) {
            PurchasePositionEntity pos = position(project, SourcingPath.AGREED, s);
            mockMvc.perform(delete(POSITIONS + "/" + pos.getId()).header("Accept-Language", "pl"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(PL_DELETE));
            assertThat(em.find(PurchasePositionEntity.class, pos.getId())).isNotNull();
        }
    }

    @Test
    @DisplayName("DELETE - a member MANAGER may delete; BUYER and FOREMAN may not (403)")
    void delete_onlyManagerOrAdmin() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity buyer = user("BUYER");
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity manager = memberUser("MANAGER", project);

        mockMvc.perform(delete(POSITIONS + "/" + pos.getId()).with(as(buyer, "BUYER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(POSITIONS + "/" + pos.getId()).with(as(foreman, "FOREMAN")))
                .andExpect(status().isForbidden());
        assertThat(em.find(PurchasePositionEntity.class, pos.getId())).isNotNull();

        mockMvc.perform(delete(POSITIONS + "/" + pos.getId()).with(as(manager, "MANAGER")))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("DELETE - a MANAGER outside the project gets 404 and the position survives")
    void delete_outOfScope_returns404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity outsider = user("MANAGER");

        mockMvc.perform(delete(POSITIONS + "/" + pos.getId()).with(as(outsider, "MANAGER")))
                .andExpect(status().isNotFound());
        assertThat(em.find(PurchasePositionEntity.class, pos.getId())).isNotNull();
    }
}
