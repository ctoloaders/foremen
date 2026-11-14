package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 11.3 - variant history of {@code /api/purchase-positions/{id}/variants}: replace,
 * restore, history, suggestions, stage rule, same-as-current, SITE price hiding and {@code variantCount}.
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 21.*, 18.1.
 */
class PositionVariantIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String PL_STAGE = "Na tym etapie zamiana wymaga ponownej akceptacji klienta.";
    private static final String PL_SAME = "Wybrany materiał i cena są takie same jak obecne.";
    private static final String PL_MISMATCH = "Materiał należy do innego projektu.";

    @Autowired
    private PurchasePositionVariantDao variantDao;

    private String url(PurchasePositionEntity p, String suffix) {
        return POSITIONS + "/" + p.getId() + "/variants" + suffix;
    }

    private ResultActions replace(PurchasePositionEntity p, Long materialId, String extra, RequestPostProcessor auth)
            throws Exception {
        var req = post(url(p, "/replace")).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialId\": " + materialId + ", \"reason\": \"ANALOG\""
                        + (extra == null ? "" : ", " + extra) + "}");
        return mockMvc.perform(auth == null ? req : req.with(auth));
    }

    private PurchasePositionVariantEntity addVariant(PurchasePositionEntity p, FinishingMaterialEntity m) {
        PurchasePositionVariantEntity v = new PurchasePositionVariantEntity();
        v.setPosition(p);
        v.setMaterial(m);
        v.setUnitSalePrice(new BigDecimal("55.00"));
        v.setUnitPlannedPurchasePrice(new BigDecimal("44.00"));
        v.setReason(com.foremen.dao.model.VariantReason.REJECTED);
        em.persist(v);
        em.flush();
        return v;
    }

    // --- replace ---

    @Test
    @DisplayName("replace at PLANNED - new option becomes current, the old one lands in history with its prices")
    void replace_atPlanned() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long oldMaterial = pos.getPlannedMaterial().getId();
        FinishingMaterialEntity replacement = material(null);

        replace(pos, replacement.getId(), "\"salePrice\": 120, \"plannedPurchasePrice\": 95, \"note\": \"cheaper\"", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedMaterialId").value(replacement.getId()))
                .andExpect(jsonPath("$.salePrice").value(120.0))
                .andExpect(jsonPath("$.plannedPurchasePrice").value(95.0))
                .andExpect(jsonPath("$.variantCount").value(1))
                .andExpect(jsonPath("$.hasVariants").value(true));

        mockMvc.perform(get(url(pos, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].materialId").value(oldMaterial))
                .andExpect(jsonPath("$[0].unitSalePrice").value(100.0))
                .andExpect(jsonPath("$[0].unitPlannedPurchasePrice").value(80.0))
                .andExpect(jsonPath("$[0].reason").value("ANALOG"))
                .andExpect(jsonPath("$[0].note").value("cheaper"));
    }

    @Test
    @DisplayName("replace - the same material with new prices is a valid re-pricing; PROPOSED is allowed")
    void replace_repricing_atProposed() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PROPOSED);

        replace(pos, pos.getPlannedMaterial().getId(), "\"salePrice\": 150", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salePrice").value(150.0))
                .andExpect(jsonPath("$.variantCount").value(1));
    }

    @Test
    @DisplayName("replace - MANUAL from ACCEPTED on -> 409 variant.stageNotAllowed")
    void replace_afterProposed_returns409() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        Long replacement = material(null).getId();
        for (PositionStatus s : new PositionStatus[] {
                PositionStatus.ACCEPTED, PositionStatus.IN_PROFORMA, PositionStatus.WITH_BUYER, PositionStatus.CANCELLED}) {
            PurchasePositionEntity pos = position(project, SourcingPath.AGREED, s);
            replace(pos, replacement, null, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(PL_STAGE));
            assertThat(variantDao.findHistory(pos.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("replace - same material and same prices -> 409 variant.sameAsCurrent, no history row")
    void replace_sameAsCurrent_returns409() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        replace(pos, pos.getPlannedMaterial().getId(), null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_SAME));
        replace(pos, pos.getPlannedMaterial().getId(), "\"salePrice\": 100.00, \"plannedPurchasePrice\": 80", null)
                .andExpect(status().isConflict());
        assertThat(variantDao.findHistory(pos.getId())).isEmpty();
    }

    @Test
    @DisplayName("replace - Project_Material of another project -> 400; unknown material -> 404; negative price -> 400")
    void replace_invalidInputs() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        replace(pos, material(other).getId(), null, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(PL_MISMATCH));
        replace(pos, 999999999L, null, null).andExpect(status().isNotFound());
        replace(pos, material(null).getId(), "\"salePrice\": -1", null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("replace - FOREMAN has no UPDATE grant (403); a MANAGER outside the project gets 404")
    void replace_grantsAndScope() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long replacement = material(null).getId();
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity outsider = user("MANAGER");
        UserEntity manager = memberUser("MANAGER", project);

        replace(pos, replacement, null, as(foreman, "FOREMAN")).andExpect(status().isForbidden());
        replace(pos, replacement, null, as(outsider, "MANAGER")).andExpect(status().isNotFound());
        replace(pos, replacement, null, as(manager, "MANAGER")).andExpect(status().isOk());
    }

    // --- restore ---

    @Test
    @DisplayName("restore - history option returns as current; the displaced option is retired with reason MANUAL")
    void restore_swapsCurrentAndHistory() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long original = pos.getPlannedMaterial().getId();
        FinishingMaterialEntity replacement = material(null);
        replace(pos, replacement.getId(), "\"salePrice\": 120", null).andExpect(status().isOk());
        PurchasePositionVariantEntity retired = variantDao.findHistory(pos.getId()).get(0);

        mockMvc.perform(post(url(pos, "/" + retired.getId() + "/restore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedMaterialId").value(original))
                .andExpect(jsonPath("$.salePrice").value(100.0))
                .andExpect(jsonPath("$.variantCount").value(1));

        mockMvc.perform(get(url(pos, "")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].materialId").value(replacement.getId()))
                .andExpect(jsonPath("$[0].unitSalePrice").value(120.0))
                .andExpect(jsonPath("$[0].reason").value("MANUAL"));
    }

    @Test
    @DisplayName("restore - stage rule (409), foreign variant (404)")
    void restore_stageAndOwnership() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity accepted = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionVariantEntity v1 = addVariant(accepted, material(project));
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);

        mockMvc.perform(post(url(accepted, "/" + v1.getId() + "/restore")).header("Accept-Language", "pl"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_STAGE));
        // Variant belongs to another position.
        mockMvc.perform(post(url(planned, "/" + v1.getId() + "/restore"))).andExpect(status().isNotFound());
        mockMvc.perform(post(url(planned, "/999999999/restore"))).andExpect(status().isNotFound());
    }

    // --- history / suggestions ---

    @Test
    @DisplayName("history - newest retired option first")
    void history_newestFirst() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        FinishingMaterialEntity m2 = material(null);
        FinishingMaterialEntity m3 = material(null);
        Long m1 = pos.getPlannedMaterial().getId();

        replace(pos, m2.getId(), null, null).andExpect(status().isOk());
        replace(pos, m3.getId(), null, null).andExpect(status().isOk());

        mockMvc.perform(get(url(pos, "")))
                .andExpect(jsonPath("$[*].materialId").value(contains(
                        Math.toIntExact(m2.getId()), Math.toIntExact(m1))));
    }

    @Test
    @DisplayName("suggestions - same-type analogs of the project, never the current item or other projects; limit applies")
    void suggestions() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity sibling = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        position(other, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long siblingMaterial = sibling.getPlannedMaterial().getId();
        PurchasePositionEntity third = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long thirdMaterial = third.getPlannedMaterial().getId();
        Long currentMaterial = pos.getPlannedMaterial().getId();

        mockMvc.perform(get(url(pos, "/suggestions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].materialId").value(hasItem(Math.toIntExact(siblingMaterial))))
                .andExpect(jsonPath("$[*].materialId").value(hasItem(Math.toIntExact(thirdMaterial))))
                .andExpect(jsonPath("$[*].materialId").value(not(hasItem(Math.toIntExact(currentMaterial)))))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get(url(pos, "/suggestions")).param("limit", "1"))
                .andExpect(jsonPath("$", hasSize(1)));

        // After a replace the retired option is itself suggested (history source).
        FinishingMaterialEntity replacement = material(null);
        replace(pos, replacement.getId(), null, null).andExpect(status().isOk());
        mockMvc.perform(get(url(pos, "/suggestions")))
                .andExpect(jsonPath("$[*].materialId").value(hasItem(Math.toIntExact(currentMaterial))))
                .andExpect(jsonPath("$[*].materialId").value(not(hasItem(Math.toIntExact(replacement.getId())))));
    }

    // --- price visibility ---

    @Test
    @DisplayName("SITE (FOREMAN) reads history and suggestions without any price; staff sees them")
    void siteSeesNoVariantPrices() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        addVariant(pos, material(project));
        position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity buyer = user("BUYER");

        RequestPostProcessor site = as(foreman, "FOREMAN");
        mockMvc.perform(get(url(pos, "")).with(site))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].unitSalePrice").value(nullValue()))
                .andExpect(jsonPath("$[0].unitPlannedPurchasePrice").value(nullValue()));
        mockMvc.perform(get(url(pos, "/suggestions")).with(site))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].salePrice").value(nullValue()));

        mockMvc.perform(get(url(pos, "")).with(as(buyer, "BUYER")))
                .andExpect(jsonPath("$[0].unitSalePrice").value(55.0))
                .andExpect(jsonPath("$[0].unitPlannedPurchasePrice").value(44.0));
        mockMvc.perform(get(url(pos, "/suggestions")).with(as(buyer, "BUYER")))
                .andExpect(jsonPath("$[0].salePrice").value(100.0));
    }

    @Test
    @DisplayName("history / suggestions of a position outside the caller's projects -> 404")
    void variantReads_outOfScope_return404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity outsider = user("FOREMAN");

        mockMvc.perform(get(url(pos, "")).with(as(outsider, "FOREMAN"))).andExpect(status().isNotFound());
        mockMvc.perform(get(url(pos, "/suggestions")).with(as(outsider, "FOREMAN"))).andExpect(status().isNotFound());
    }

    // --- variantCount ---

    @Test
    @DisplayName("variantCount - present on list, detail and extended reads (0 without history)")
    void variantCount_inReads() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity withHistory = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity plain = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        addVariant(withHistory, material(project));
        addVariant(withHistory, material(project));

        mockMvc.perform(get(POSITIONS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + withHistory.getId() + ")].variantCount")
                        .value(hasItem(2)))
                .andExpect(jsonPath("$.content[?(@.id == " + plain.getId() + ")].variantCount")
                        .value(hasItem(0)));
        mockMvc.perform(get(POSITIONS + "/" + withHistory.getId()))
                .andExpect(jsonPath("$.variantCount").value(2))
                .andExpect(jsonPath("$.hasVariants").value(true));
        mockMvc.perform(get(POSITIONS + "/" + plain.getId()))
                .andExpect(jsonPath("$.variantCount").value(0))
                .andExpect(jsonPath("$.hasVariants").value(false));
    }
}
