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
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceModel;
import com.foremen.service.model.mapper.WorkVolumeFormulaServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * CRUD service for {@link WorkVolumeFormulaEntity} (FOR-05-04, Requirement 2; task 18.3).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} — a work's default volume formula is a work-catalog child row
 * with no project boundary (mirroring {@code WorkMaterialConsumptionService}, per
 * {@code .kiro/steering/entity-creation-rules.md} step 4).
 *
 * <p><b>Parse + validate + derive {@code parsedAst} on write.</b> {@link WorkVolumeFormulaServiceMapper}
 * deliberately ignores {@code parsedAst} on the write side (it is never client-settable), so this
 * service runs the write model's {@code sourceText} through {@link FormulaParser#parse(String)}
 * then {@link FormulaValidator#validate(FormulaAst, Set)} and sets the resulting AST onto the
 * entity itself, inside the same create/update transaction, immediately after the generic
 * {@link AdminService} mapper step has produced/mutated the entity:
 * <ul>
 *   <li>{@link #afterCreate(WorkVolumeFormulaEntity)} parses+validates the just-created entity's
 *       {@code sourceText} and re-saves it with the derived {@code parsedAst} (the entity is
 *       already flushed with a generated id at that point, matching the {@code AdminService}
 *       create contract);</li>
 *   <li>{@link #update(Long, WorkVolumeFormulaServiceExtendedModel)} is overridden to run the same
 *       derivation on the incoming {@code sourceText} <em>before</em> delegating to the generic
 *       update, so the persisted row never has a {@code parsedAst} that drifts from
 *       {@code sourceText} (Requirement 2.6).</li>
 * </ul>
 * {@code knownWorkRefs} is every {@link WorkItemEntity#getCode()} currently in the catalog (the
 * natural "known work" universe for a catalog-level formula) — resolved via {@link WorkItemDao}.
 * A malformed or semantically-invalid formula propagates the {@code ForemenApiException} thrown
 * by {@link FormulaParser}/{@link FormulaValidator} as-is, rolling back the transaction.
 */
@Service
@RequiredArgsConstructor
public class WorkVolumeFormulaService implements AdminService<
        WorkVolumeFormulaServiceModel, WorkVolumeFormulaServiceExtendedModel,
        WorkVolumeFormulaEntity, Long> {

    private final WorkVolumeFormulaDao dao;
    private final WorkVolumeFormulaServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final WorkItemDao workItemDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;

    @Override
    public WorkVolumeFormulaDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkVolumeFormulaEntity, WorkVolumeFormulaServiceModel,
            WorkVolumeFormulaServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkVolumeFormulaEntity> getDaoModelClass() {
        return WorkVolumeFormulaEntity.class;
    }

    /**
     * Overrides the generic create so the derived {@code parsedAst} is set on the entity
     * <em>before</em> it is first persisted, rather than relying on
     * {@link AdminService#create}'s flush-then-{@code afterCreate} sequence.
     *
     * <p>{@code parsedAst} is a {@code NOT NULL} column
     * ({@code WorkVolumeFormulaEntity.parsedAst}, {@code 085-create-work-volume-formulas.xml}),
     * but {@link com.foremen.service.model.mapper.WorkVolumeFormulaServiceMapper} deliberately
     * never sets it from client input (Requirement 2.6 — it is always server-derived), and the
     * service-layer write model ({@link WorkVolumeFormulaServiceExtendedModel}) carries no
     * {@code parsedAst} field for the mapper to even read. The generic {@link AdminService#create}
     * flow maps the model straight to an entity, then {@code save}s and {@code flush}es it —
     * <em>before</em> calling {@link #afterCreate(WorkVolumeFormulaEntity)} — so deriving
     * {@code parsedAst} only in {@code afterCreate} violates the {@code NOT NULL} constraint on
     * every create, before the cycle/validation check ever runs. This override reproduces the
     * generic {@code create} contract (validate, map, save+flush, {@code afterCreate}, audit,
     * return) but maps the entity itself first so {@code parsedAst} can be set on it before that
     * first {@code save}.
     */
    @Override
    public WorkVolumeFormulaServiceExtendedModel create(WorkVolumeFormulaServiceExtendedModel model) {
        validateCreate(model);
        WorkVolumeFormulaEntity entity = getMapper().toCreateDaoModel(model);
        entity.setParsedAst(parseAndValidate(entity.getSourceText()));
        rejectIfIntroducesCatalogCycle(entity.getWorkItem().getCode(), entity.getParsedAst());
        entity = getWriteDao().save(entity);
        getEntityManager().flush();
        afterCreate(entity);
        saveAudit(null, entity, "CREATE");
        return getMapper().toServiceExtendedModel(entity);
    }

    /**
     * No-op: {@code parsedAst} is now derived and persisted inside {@link #create}'s own
     * save-before-flush sequence, so there is nothing left for the generic post-create hook to
     * do. Retained (rather than removed) so a future revert of the {@link #create} override has
     * an obvious place to restore the previous derive-after-flush behaviour.
     */
    @Override
    public void afterCreate(WorkVolumeFormulaEntity entity) {
        // Intentionally empty — see #create.
    }

    /**
     * Overrides the generic update so the derived {@code parsedAst} is computed from the
     * incoming {@code sourceText} before the row is persisted, keeping the two fields from ever
     * drifting apart (Requirement 2.6).
     */
    @Override
    public WorkVolumeFormulaServiceExtendedModel update(Long id, WorkVolumeFormulaServiceExtendedModel model) {
        AdminService.super.update(id, model);
        WorkVolumeFormulaEntity entity = getDao().findById(id).orElseThrow();
        entity.setParsedAst(parseAndValidate(entity.getSourceText()));
        rejectIfIntroducesCatalogCycle(entity.getWorkItem().getCode(), entity.getParsedAst());
        getDao().save(entity);
        return getMapper().toServiceExtendedModel(entity);
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
     * Catalog-wide cycle check at save (Requirement 3.3). Design.md's §6.3 {@code planOrder} is
     * defined over a single room's formula set, but a cycle among cross-work references is a
     * property of the formulas' reference graph alone (it does not depend on which room they are
     * evaluated for), so this method reuses the same planner over the FULL catalog of currently
     * persisted formulas (every {@link WorkVolumeFormulaEntity} default formula plus every
     * {@link WorkPackageOverrideEntity} override formula), keyed by the owning work item's
     * {@link WorkItemEntity#getCode()}, with the candidate formula being saved substituted in for
     * its own work ref. This is the least invasive scope that still rejects any cycle that could
     * ever surface at room-evaluation time, since a room's reference graph is always a subset of
     * the catalog's (only works actually present together in a room matter for the narrower R3.2
     * ordering, but ANY cycle in the catalog graph would manifest the moment those works share a
     * room — so catching it here, at save, satisfies R3.3's "reject the save/edit operation that
     * would introduce it" without waiting for a specific room to exist).
     *
     * <p>{@link FormulaEvaluationPlanner#planOrder} throws {@code 409 error.formula.cycle} on a
     * back-edge; letting that exception propagate here rolls back the transaction, so the
     * candidate formula is never persisted (R3.3).
     *
     * @param workItemCode the owning work item's code (the work ref key used throughout the
     *                     formula engine); a {@code null}/blank code means this work item cannot
     *                     be referenced by any other formula, so no cycle check is possible or
     *                     necessary
     * @param candidateAst the just-parsed+validated AST being saved for {@code workItemCode}
     */
    private void rejectIfIntroducesCatalogCycle(String workItemCode, FormulaAst candidateAst) {
        if (workItemCode == null || workItemCode.isBlank()) {
            return;
        }
        Map<String, FormulaAst> catalogFormulas = new HashMap<>();
        for (WorkVolumeFormulaEntity other : dao.findAllWithWorkItem()) {
            String otherCode = other.getWorkItem().getCode();
            if (otherCode != null && !otherCode.isBlank() && !otherCode.equals(workItemCode)) {
                catalogFormulas.put(otherCode, other.getParsedAst());
            }
        }
        for (WorkPackageOverrideEntity override : workPackageOverrideDao.findAllWithWorkItemAndPackage()) {
            String overrideCode = override.getWorkItem().getCode();
            if (overrideCode != null && !overrideCode.isBlank() && !overrideCode.equals(workItemCode)
                    && override.getOverrideParsedAst() != null) {
                // A work with both a default and an override formula only contributes one node to
                // the reference graph; the override (package-specific) formula is the one that
                // would actually be evaluated together with other overrides in a package context,
                // so it takes precedence over an already-collected default for the same code.
                catalogFormulas.put(overrideCode, override.getOverrideParsedAst());
            }
        }
        catalogFormulas.put(workItemCode, candidateAst);

        FormulaEvaluationPlanner.planOrder(catalogFormulas);
    }
}
