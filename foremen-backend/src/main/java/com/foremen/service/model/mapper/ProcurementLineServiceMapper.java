package com.foremen.service.model.mapper;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.PurchaseLineServiceModel;

/**
 * FOR-06-04 (design §5.1, §5.3) — the {@link ServiceToDaoMapper} the project-scoped
 * {@code PurchaseLineReconciliationService} supplies to {@code getMapper()}.
 *
 * <p>The reconciliation service is a {@code ProjectScopedService} <strong>over the line</strong>,
 * but its surface is entirely bespoke: re-link / clear-match / classify / clear-classification are
 * targeted single-field mutations (not the generic create/update contract), and the reconciled list
 * is projected by the pure static {@link PurchaseLineServiceMapper} because each row carries two
 * <strong>externally-resolved localized names</strong> (the matched-material name from the FOR-06-03
 * {@code PlannedMaterialCandidateProvider}, the unplanned-category name from the loaded dictionary
 * row) that a declarative bean-to-bean mapper cannot resolve on its own (R9.1).
 *
 * <p>This mapper therefore exists only to satisfy the {@code ProjectScopedService} /
 * {@code AdminService} plumbing contract ({@code getMapper()} must be non-null so the inherited
 * project-scope guard and specification machinery work). Its read projections delegate to the static
 * {@link PurchaseLineServiceMapper} with a {@code null} matched-material name — the service fills both
 * localized names itself — and the generic write mappings are unused on this service (every write
 * goes through a reconciliation endpoint) and throw to fail loudly if ever wired by mistake.
 */
@Component
public class ProcurementLineServiceMapper
        implements ServiceToDaoMapper<ProcurementLineEntity, PurchaseLineServiceModel,
        PurchaseLineServiceModel> {

    @Override
    public Set<String> getI18nSupportedProperties() {
        // The transactional line carries no nameRU/namePL columns (R9.1); nothing is locale-swapped.
        return Set.of();
    }

    // --- Read path (used only by the inherited generic reads; the list uses the static mapper) ----

    @Override
    public PurchaseLineServiceModel toServiceModel(ProcurementLineEntity source) {
        // Matched-material name is resolved by the service from the candidate projection; left null
        // here so this projection stays pure (no I/O). The unplanned-category name resolves inline.
        return source == null ? null : PurchaseLineServiceMapper.toServiceModel(source, null);
    }

    @Override
    public PurchaseLineServiceModel toServiceExtendedModel(ProcurementLineEntity source) {
        return toServiceModel(source);
    }

    // --- Write path (never invoked: all writes go through reconciliation endpoints) ----------------

    @Override
    public ProcurementLineEntity toCreateDaoModel(PurchaseLineServiceModel source) {
        throw new UnsupportedOperationException(
                "ProcurementLineReconciliationService creates no lines via the generic contract; "
                        + "lines are produced by FOR-06-03 intake and mutated via reconciliation endpoints.");
    }

    @Override
    public void updateFields(PurchaseLineServiceModel source, ProcurementLineEntity target) {
        throw new UnsupportedOperationException(
                "ProcurementLineReconciliationService updates lines via targeted reconciliation "
                        + "endpoints, not the generic update contract.");
    }
}
