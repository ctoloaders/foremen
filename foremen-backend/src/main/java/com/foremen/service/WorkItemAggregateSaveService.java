package com.foremen.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.WorkPackageOverrideServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;

import lombok.RequiredArgsConstructor;

/**
 * Applies all pending edits of a work item (attributes, default formula, package overrides,
 * room-type attachment, labour price, consumption norms) in ONE transaction (FOR-04-11 bug-report
 * Round 2, item 1). Delegates to the existing per-entity services so their validation, derived
 * formula ASTs and audit trail stay in force; any failure rolls back every part.
 */
@Service
@RequiredArgsConstructor
public class WorkItemAggregateSaveService {

    private final WorkItemService workItemService;
    private final WorkItemDao workItemDao;
    private final WorkVolumeFormulaService formulaService;
    private final WorkVolumeFormulaDao formulaDao;
    private final WorkPackageOverrideService overrideService;
    private final WorkPackageOverrideDao overrideDao;
    private final WorkPriceService priceService;
    private final WorkPriceDao priceDao;
    private final WorkMaterialConsumptionService consumptionService;
    private final WorkMaterialConsumptionDao consumptionDao;
    private final WorkRelatedWorkService relatedWorkService;

    /** Service-layer command; {@code null} parts are skipped. */
    public record Command(
            WorkItemServiceExtendedModel workItem,
            boolean formulaPresent,
            String formulaSourceText,
            List<OverrideChange> packageOverrides,
            List<Long> roomTypeIds,
            boolean pricePresent,
            Long priceCurrencyId,
            BigDecimal priceNet,
            List<ConsumptionChange> consumptions,
            List<Long> relatedWorkIds) {
    }

    public record OverrideChange(Long offerPackageId, boolean member, String overrideSourceText) {
    }

    public record ConsumptionChange(Long id, boolean delete, WorkMaterialConsumptionServiceExtendedModel data) {
    }

    @Transactional
    public void save(Long workItemId, Command cmd) {
        if (!workItemDao.existsById(workItemId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", workItemId);
        }
        if (cmd.workItem() != null) {
            workItemService.update(workItemId, cmd.workItem());
        }
        if (cmd.formulaPresent()) {
            applyFormula(workItemId, cmd.formulaSourceText());
        }
        if (cmd.packageOverrides() != null) {
            for (OverrideChange change : cmd.packageOverrides()) {
                applyOverride(workItemId, change);
            }
        }
        if (cmd.roomTypeIds() != null) {
            workItemService.setRoomTypeIds(workItemId, cmd.roomTypeIds());
        }
        if (cmd.pricePresent()) {
            applyPrice(workItemId, cmd.priceCurrencyId(), cmd.priceNet());
        }
        if (cmd.consumptions() != null) {
            for (ConsumptionChange change : cmd.consumptions()) {
                applyConsumption(workItemId, change);
            }
        }
        if (cmd.relatedWorkIds() != null) {
            relatedWorkService.setRelatedIds(workItemId, cmd.relatedWorkIds());
        }
    }

    private void applyFormula(Long workItemId, String sourceText) {
        var existing = formulaDao.findByWorkItemId(workItemId);
        boolean blank = sourceText == null || sourceText.isBlank();
        if (blank) {
            existing.ifPresent(f -> formulaService.deleteById(f.getId()));
            return;
        }
        var model = new WorkVolumeFormulaServiceExtendedModel(null, workItemId, sourceText.trim());
        if (existing.isPresent()) {
            formulaService.update(existing.get().getId(), model);
        } else {
            formulaService.create(model);
        }
    }

    private void applyOverride(Long workItemId, OverrideChange change) {
        String text = change.overrideSourceText() == null || change.overrideSourceText().isBlank()
                ? null : change.overrideSourceText().trim();
        var existing = overrideDao.findByWorkItemIdAndOfferPackageId(workItemId, change.offerPackageId());
        if (!change.member() && text == null) {
            existing.ifPresent(o -> overrideService.deleteById(o.getId()));
            return;
        }
        var model = new WorkPackageOverrideServiceExtendedModel(
                null, workItemId, change.offerPackageId(), change.member(), text);
        if (existing.isPresent()) {
            overrideService.update(existing.get().getId(), model);
        } else {
            overrideService.create(model);
        }
    }

    private void applyPrice(Long workItemId, Long currencyId, BigDecimal netPrice) {
        var existing = priceDao.findByWorkItemId(workItemId);
        if (netPrice == null) {
            existing.ifPresent(p -> priceService.deleteById(p.getId()));
            return;
        }
        var model = new WorkPriceServiceExtendedModel(workItemId, currencyId, netPrice);
        if (existing.isPresent()) {
            priceService.update(existing.get().getId(), model);
        } else {
            priceService.create(model);
        }
    }

    private void applyConsumption(Long workItemId, ConsumptionChange change) {
        if (change.id() == null) {
            if (!change.delete()) {
                consumptionService.create(change.data());
            }
            return;
        }
        WorkMaterialConsumptionEntity existing = consumptionDao.findById(change.id())
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, "error.entity.not.found", change.id()));
        if (existing.getWorkItem() == null || !workItemId.equals(existing.getWorkItem().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", change.id());
        }
        if (change.delete()) {
            consumptionService.deleteById(change.id());
        } else {
            consumptionService.update(change.id(), change.data());
        }
    }
}
