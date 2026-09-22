package com.foremen.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.formula.FormulaEvaluationPlanner;
import com.foremen.service.formula.FormulaParser;
import com.foremen.service.formula.FormulaValidator;
import com.foremen.service.model.WorkPackageOverrideServiceExtendedModel;
import com.foremen.service.model.WorkPackageOverrideServiceModel;
import com.foremen.service.model.mapper.WorkPackageOverrideServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * CRUD service for {@link WorkPackageOverrideEntity} (FOR-05-04, Requirement 4; task 18.3).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} — a {@code (WorkItem, OfferPackage)} override is a work-catalog
 * child row with no project boundary (mirroring {@code WorkMaterialConsumptionService}, per
 * {@code .kiro/steering/entity-creation-rules.md} step 4).
 *
 * <p><b>Present override text ⇒ member (Requirement 4.2).</b> Whenever {@code overrideSourceText}
 * is non-blank, {@code member} is forced to {@code true} regardless of what the client sent — the
 * more permissive of the two possible enforcement directions, since a client that bothered to
 * supply an override formula clearly intends membership. Flag-only membership (no override text)
 * is left exactly as the client set it (Requirement 4.1, 4.3).
 *
 * <p><b>Parse + validate + derive {@code overrideParsedAst} on write.</b> Mirrors
 * {@code WorkVolumeFormulaService}'s derivation: {@link WorkPackageOverrideServiceMapper} ignores
 * {@code overrideParsedAst} on write, so this service parses+validates
 * {@code overrideSourceText} (when present) via {@link FormulaParser}/{@link FormulaValidator} and
 * sets the derived AST on the entity within the same create/update transaction. A blank/absent
 * override text leaves {@code overrideParsedAst} {@code null} (flag-only membership, no formula to
 * derive — Requirement 4.1, 4.3). {@code knownWorkRefs} is every {@link WorkItemEntity#getCode()}
 * currently in the catalog, matching {@code WorkVolumeFormulaService}.
 */
@Service
@RequiredArgsConstructor
public class WorkPackageOverrideService implements AdminService<
        WorkPackageOverrideServiceModel, WorkPackageOverrideServiceExtendedModel,
        WorkPackageOverrideEntity, Long> {

    private final WorkPackageOverrideDao dao;
    private final WorkPackageOverrideServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final WorkItemDao workItemDao;
    private final WorkVolumeFormulaDao workVolumeFormulaDao;

    @Override
    public WorkPackageOverrideDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkPackageOverrideEntity, WorkPackageOverrideServiceModel,
            WorkPackageOverrideServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkPackageOverrideEntity> getDaoModelClass() {
        return WorkPackageOverrideEntity.class;
    }

    /**
     * Enforces "present override ⇒ member" on the just-created entity, derives
     * {@code overrideParsedAst} from {@code overrideSourceText} (if present), and re-saves it in
     * the same create transaction (Requirement 4.2, 4.4).
     */
    @Override
    public void afterCreate(WorkPackageOverrideEntity entity) {
        applyMemberAndDerivedAst(entity);
        rejectIfIntroducesCatalogCycle(entity);
        getDao().save(entity);
    }

    /**
     * Overrides the generic update so "present override ⇒ member" and the derived
     * {@code overrideParsedAst} are (re)computed from the incoming state before the row is
     * persisted, keeping the fields from ever drifting apart (Requirement 4.2, 4.4).
     */
    @Override
    public WorkPackageOverrideServiceExtendedModel update(Long id, WorkPackageOverrideServiceExtendedModel model) {
        AdminService.super.update(id, model);
        WorkPackageOverrideEntity entity = getDao().findById(id).orElseThrow();
        applyMemberAndDerivedAst(entity);
        rejectIfIntroducesCatalogCycle(entity);
        getDao().save(entity);
        return getMapper().toServiceExtendedModel(entity);
    }

    /**
     * "Present override ⇒ member" (Requirement 4.2) plus derived {@code overrideParsedAst}: a
     * non-blank {@code overrideSourceText} forces {@code member = true} and is parsed+validated
     * into {@code overrideParsedAst}; a blank/absent override text leaves {@code member} as-is and
     * clears {@code overrideParsedAst} (flag-only membership, Requirement 4.1, 4.3).
     */
    private void applyMemberAndDerivedAst(WorkPackageOverrideEntity entity) {
        String overrideSourceText = entity.getOverrideSourceText();
        if (overrideSourceText != null && !overrideSourceText.isBlank()) {
            entity.setMember(true);
            entity.setOverrideParsedAst(parseAndValidate(overrideSourceText));
        } else {
            entity.setOverrideParsedAst(null);
        }
    }

    /** Parses {@code sourceText} and validates it against the catalog's known work-item codes. */
    private FormulaAst parseAndValidate(String sourceText) {
        FormulaAst ast = FormulaParser.parse(sourceText);
        FormulaValidator.validate(ast, knownWorkRefs());
        return ast;
    }

    /** Every {@link WorkItemEntity#getCode()} currently in the catalog (non-null codes only). */
    private Set<String> knownWorkRefs() {
        Set<String> refs = new HashSet<>();
        for (WorkItemEntity item : workItemDao.findAll()) {
            String code = item.getCode();
            if (code != null && !code.isBlank()) {
                refs.add(code);
            }
        }
        return refs;
    }

    /**
     * Catalog-wide cycle check at save (Requirement 3.3), mirroring
     * {@code WorkVolumeFormulaService#rejectIfIntroducesCatalogCycle} (see that method's Javadoc
     * for the scope rationale — the check runs over the full catalog of formulas, not a single
     * room, since a reference cycle is a property of the formula graph alone). If
     * {@code entity.overrideParsedAst} is {@code null} (flag-only membership, no override
     * formula), there is nothing to check — a row with no formula cannot introduce a cycle.
     *
     * <p>The candidate override formula is substituted into the catalog graph under its owning
     * work item's code, taking precedence over that work's default formula (if any) for the same
     * reasoning as the mirrored method: the override is what will actually be evaluated once this
     * package context is active.
     *
     * @param entity the just-derived {@link WorkPackageOverrideEntity} being saved
     */
    private void rejectIfIntroducesCatalogCycle(WorkPackageOverrideEntity entity) {
        FormulaAst candidateAst = entity.getOverrideParsedAst();
        if (candidateAst == null) {
            return;
        }
        String workItemCode = entity.getWorkItem().getCode();
        if (workItemCode == null || workItemCode.isBlank()) {
            return;
        }
        Map<String, FormulaAst> catalogFormulas = new HashMap<>();
        for (WorkVolumeFormulaEntity formula : workVolumeFormulaDao.findAllWithWorkItem()) {
            String code = formula.getWorkItem().getCode();
            if (code != null && !code.isBlank() && !code.equals(workItemCode)) {
                catalogFormulas.put(code, formula.getParsedAst());
            }
        }
        for (WorkPackageOverrideEntity other : dao.findAllWithWorkItemAndPackage()) {
            String code = other.getWorkItem().getCode();
            if (code != null && !code.isBlank() && !code.equals(workItemCode)
                    && other.getOverrideParsedAst() != null
                    && !other.getId().equals(entity.getId())) {
                catalogFormulas.put(code, other.getOverrideParsedAst());
            }
        }
        catalogFormulas.put(workItemCode, candidateAst);

        FormulaEvaluationPlanner.planOrder(catalogFormulas);
    }
}
