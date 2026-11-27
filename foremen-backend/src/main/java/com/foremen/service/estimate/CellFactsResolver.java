package com.foremen.service.estimate;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.service.estimateai.EngineFacts;
import com.foremen.service.formula.VolumeResolver;

import jakarta.persistence.EntityManager;

/**
 * FOR-05-04 (design B11, Requirement 21.2): the engine facts of one {@code (work, room)} cell. Calls
 * {@link VolumeResolver#resolve} with the same inputs the assign path uses (package override, default
 * formula, unit code, room dimensions), so the formula volume is known <b>also for an overridden cell</b>,
 * whose stored quantity is the manual or model value.
 *
 * <p>Also supplies the {@link EngineFacts} the job needs (formula volume and
 * category per work). Reads only; never writes.
 */
@Component
public class CellFactsResolver {

    /** {@code volume.source} when an applicable formula gave the value. */
    public static final String SOURCE_FORMULA = "FORMULA";
    /** {@code volume.source} when the unit-to-dimension fallback gave the value. */
    public static final String SOURCE_UNIT_FALLBACK = "UNIT_FALLBACK";
    /** {@code formulaKey} of the fallback, the same key the estimate matrix uses. */
    public static final String FALLBACK_KEY = "fallback";

    /**
     * Engine facts of a cell.
     *
     * @param formulaVolume what the formula (or the unit fallback) gives for the room, never {@code null}
     * @param source        {@link #SOURCE_FORMULA} or {@link #SOURCE_UNIT_FALLBACK}
     * @param formulaKey    the applicable formula's source text, {@link #FALLBACK_KEY} for the fallback,
     *                      or {@code null} when a formula applies but has no source text
     * @param fallbackUsed  {@code true} iff the unit fallback supplied the value
     */
    public record Facts(BigDecimal formulaVolume, String source, String formulaKey, boolean fallbackUsed) {
    }

    private final WorkVolumeFormulaDao workVolumeFormulaDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;
    private final OfferPackageDao offerPackageDao;
    private final EntityManager entityManager;

    public CellFactsResolver(WorkVolumeFormulaDao workVolumeFormulaDao,
                             WorkPackageOverrideDao workPackageOverrideDao,
                             OfferPackageDao offerPackageDao,
                             EntityManager entityManager) {
        this.workVolumeFormulaDao = workVolumeFormulaDao;
        this.workPackageOverrideDao = workPackageOverrideDao;
        this.offerPackageDao = offerPackageDao;
        this.entityManager = entityManager;
    }

    /**
     * The facts of {@code work} in {@code room} under {@code packageCode} (the package whose formula
     * override the assign path would use; {@code null} or unknown means none).
     */
    public Facts facts(WorkItemEntity work, RoomEntity room, String packageCode) {
        if (work == null || room == null) {
            return null;
        }
        WorkVolumeFormulaEntity defaultFormula = workVolumeFormulaDao.findByWorkItemId(work.getId()).orElse(null);
        WorkPackageOverrideEntity override = null;
        if (packageCode != null && !packageCode.isBlank()) {
            OfferPackageEntity pkg = offerPackageDao.findByCode(packageCode).orElse(null);
            if (pkg != null) {
                override = workPackageOverrideDao.findByWorkItemIdAndOfferPackageId(work.getId(), pkg.getId())
                        .orElse(null);
            }
        }
        String unitCode = work.getUnit() != null ? work.getUnit().getCode() : null;
        VolumeResolver.Resolution resolution = VolumeResolver.resolve(override, defaultFormula, unitCode, room);
        return toFacts(resolution, override, defaultFormula);
    }

    /** Pure mapping of a resolver result to the facts (package-visible for tests). */
    static Facts toFacts(VolumeResolver.Resolution resolution, WorkPackageOverrideEntity override,
                         WorkVolumeFormulaEntity defaultFormula) {
        if (resolution.fallbackUsed()) {
            return new Facts(resolution.volume(), SOURCE_UNIT_FALLBACK, FALLBACK_KEY, true);
        }
        String key = override != null && override.getOverrideParsedAst() != null
                ? override.getOverrideSourceText()
                : (defaultFormula != null ? defaultFormula.getSourceText() : null);
        return new Facts(resolution.volume(), SOURCE_FORMULA, key, false);
    }

    /** The facts by ids; {@code null} when the work or the room does not exist. */
    public Facts facts(Long workItemId, Long roomId, String packageCode) {
        if (workItemId == null || roomId == null) {
            return null;
        }
        return facts(entityManager.find(WorkItemEntity.class, workItemId),
                entityManager.find(RoomEntity.class, roomId), packageCode);
    }

    /** The job view of the engine (formula volume and category per work) under {@code packageCode}. */
    public EngineFacts engineFacts(String packageCode) {
        return new EngineFacts() {
            @Override
            public BigDecimal formulaVolume(Long workItemId, Long roomId) {
                Facts f = facts(workItemId, roomId, packageCode);
                return f != null ? f.formulaVolume() : null;
            }

            @Override
            public Long categoryId(Long workItemId) {
                WorkItemEntity work = workItemId != null ? entityManager.find(WorkItemEntity.class, workItemId) : null;
                return work != null && work.getWorkCategory() != null ? work.getWorkCategory().getId() : null;
            }
        };
    }
}
