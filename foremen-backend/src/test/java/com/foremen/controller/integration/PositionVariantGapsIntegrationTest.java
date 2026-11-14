package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VariantReason;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;

/**
 * FOR-11-00 task 11.6 - gaps of Requirement 21 left by {@link PositionVariantIntegrationTest}: audit of
 * replace / restore (21.8), restore stage and no-op rules (21.4, 21.7), suggestions merge, type filter and
 * default / maximum limit (21.6), and cascade on position delete (21.9).
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 21.4, 21.6, 21.7, 21.8, 21.9.
 */
class PositionVariantGapsIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String AUDIT_CLASS = "PurchasePositionEntity";

    @Autowired
    private PurchasePositionVariantDao variantDao;

    @Autowired
    private AuditLogDao auditLogDao;

    private String url(PurchasePositionEntity p, String suffix) {
        return POSITIONS + "/" + p.getId() + "/variants" + suffix;
    }

    private ResultActions replace(PurchasePositionEntity p, Long materialId) throws Exception {
        return mockMvc.perform(post(url(p, "/replace")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialId\": " + materialId + ", \"reason\": \"ANALOG\"}"));
    }

    private PurchasePositionVariantEntity addVariant(PurchasePositionEntity p, FinishingMaterialEntity m,
                                                     String sale, String purchase) {
        PurchasePositionVariantEntity v = new PurchasePositionVariantEntity();
        v.setPosition(p);
        v.setMaterial(m);
        v.setUnitSalePrice(new BigDecimal(sale));
        v.setUnitPlannedPurchasePrice(new BigDecimal(purchase));
        v.setReason(VariantReason.REJECTED);
        em.persist(v);
        em.flush();
        return v;
    }

    private List<AuditLogEntity> audit(PurchasePositionEntity p) {
        em.flush();
        return auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc(AUDIT_CLASS, p.getId());
    }

    // --- audit (21.8) ---

    @Test
    @DisplayName("replace and restore each write one UPDATE audit row for the position")
    void replaceAndRestore_areAudited() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        int baseline = audit(pos).size();

        replace(pos, material(null).getId()).andExpect(status().isOk());
        List<AuditLogEntity> afterReplace = audit(pos);
        assertThat(afterReplace).hasSize(baseline + 1);
        AuditLogEntity replaced = afterReplace.get(afterReplace.size() - 1);
        assertThat(replaced.getOperation()).isEqualTo("UPDATE");
        assertThat(replaced.getSnapshotBefore()).isNotNull();
        assertThat(replaced.getSnapshotAfter()).isNotNull().isNotEqualTo(replaced.getSnapshotBefore());

        PurchasePositionVariantEntity retired = variantDao.findHistory(pos.getId()).get(0);
        mockMvc.perform(post(url(pos, "/" + retired.getId() + "/restore"))).andExpect(status().isOk());
        List<AuditLogEntity> afterRestore = audit(pos);
        assertThat(afterRestore).hasSize(baseline + 2);
        assertThat(afterRestore.get(afterRestore.size() - 1).getOperation()).isEqualTo("UPDATE");
    }

    // --- restore rules (21.4, 21.7) ---

    @Test
    @DisplayName("restore is allowed at PROPOSED and the restored row leaves the history")
    void restore_atProposed() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PROPOSED);
        FinishingMaterialEntity old = material(project);
        PurchasePositionVariantEntity v = addVariant(pos, old, "55.00", "44.00");

        mockMvc.perform(post(url(pos, "/" + v.getId() + "/restore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedMaterialId").value(old.getId()))
                .andExpect(jsonPath("$.salePrice").value(55.0))
                .andExpect(jsonPath("$.plannedPurchasePrice").value(44.0));
        em.flush();
        em.clear();
        assertThat(variantDao.findById(v.getId())).isEmpty();
        assertThat(variantDao.findHistory(pos.getId())).hasSize(1);
    }

    @Test
    @DisplayName("restore of an option identical to the current one -> 409 and the history is untouched")
    void restore_sameAsCurrent_returns409() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionVariantEntity same = addVariant(pos, pos.getPlannedMaterial(), "100.00", "80.00");

        mockMvc.perform(post(url(pos, "/" + same.getId() + "/restore")).header("Accept-Language", "pl"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Wybrany materiał i cena są takie same jak obecne."));
        assertThat(variantDao.findHistory(pos.getId())).hasSize(1);
    }

    // --- suggestions (21.6) ---

    @Test
    @DisplayName("suggestions - a material known from both a sibling's current option and a history row appears once")
    void suggestions_mergeDistinct() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity sibling = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity third = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        FinishingMaterialEntity shared = sibling.getPlannedMaterial();
        addVariant(third, shared, "70.00", "50.00");

        mockMvc.perform(get(url(pos, "/suggestions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.materialId == " + shared.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("suggestions - materials of another material type are not suggested")
    void suggestions_sameTypeOnly() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        MaterialTypeEntity otherType = new MaterialTypeEntity();
        otherType.setCode("pc_type_other_" + unique());
        otherType.setNameRU("Другой");
        otherType.setNamePL("Inny");
        otherType.setActive(true);
        em.persist(otherType);
        FinishingMaterialEntity foreign = material(project);
        foreign.setType(otherType);
        em.flush();
        position(project, foreign, SourcingPath.AGREED, PositionStatus.PLANNED);

        mockMvc.perform(get(url(pos, "/suggestions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("suggestions - default limit 10, maximum 50, a non-positive limit is clamped to 1")
    void suggestions_limits() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        for (int i = 0; i < 55; i++) {
            position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        }
        em.flush();
        em.clear();

        mockMvc.perform(get(url(pos, "/suggestions"))).andExpect(jsonPath("$", hasSize(10)));
        mockMvc.perform(get(url(pos, "/suggestions")).param("limit", "25")).andExpect(jsonPath("$", hasSize(25)));
        mockMvc.perform(get(url(pos, "/suggestions")).param("limit", "1000")).andExpect(jsonPath("$", hasSize(50)));
        mockMvc.perform(get(url(pos, "/suggestions")).param("limit", "0")).andExpect(jsonPath("$", hasSize(1)));
    }

    // --- cascade (21.9) ---

    @Test
    @DisplayName("deleting a position deletes its variants")
    void deletePosition_deletesVariants() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        Long positionId = pos.getId();
        PurchasePositionVariantEntity v1 = addVariant(pos, material(project), "55.00", "44.00");
        PurchasePositionVariantEntity v2 = addVariant(pos, material(project), "56.00", "45.00");
        em.flush();
        em.clear();

        mockMvc.perform(delete(POSITIONS + "/" + positionId)).andExpect(status().is2xxSuccessful());
        em.flush();
        em.clear();

        assertThat(variantDao.findHistory(positionId)).isEmpty();
        assertThat(variantDao.findById(v1.getId())).isEmpty();
        assertThat(variantDao.findById(v2.getId())).isEmpty();
    }
}
