package com.foremen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.service.model.FinishingMaterialServiceModel;
import com.foremen.service.model.mapper.FinishingMaterialServiceMapper;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD service for {@link FinishingMaterialEntity} (FOR-04-18, task 2.2).
 *
 * <p>The sibling of {@code ConstructionMaterialService}. A GLOBAL admin resource: it implements
 * exactly {@link AdminService} and NOT {@link ProjectScopedService} (Requirement 2.11) — finishing
 * materials are catalog rows with no project boundary, so no {@code getProjectIdPath()} is supplied.
 *
 * <p><b>Pre-persist normalization.</b> Both create and update run {@link #normalize} through the
 * {@link #validateCreate}/{@link #validateUpdate} hooks (mirroring
 * {@code ConstructionMaterialService}/{@code RoomService}). Fired inside the generic
 * {@link AdminService} create/update transaction <em>before</em> the mapper turns the model into (or
 * onto) the entity, normalization:
 * <ol>
 *   <li>resolves-and-loads every reference — mandatory {@code categoryId}/{@code materialId}/
 *       {@code unitId} and optional {@code typeId}/{@code producerId} plus each
 *       {@code offerPackageId} — rejecting a null-or-dangling mandatory id, or a non-null dangling
 *       optional id, with a field-identifying {@code 404 error.entity.not.found} (Requirements 2.3,
 *       2.4);</li>
 *   <li>rejects an empty {@code offerPackageIds} with a {@code 400} naming {@code packages}
 *       (Requirements 2.3, 3.1);</li>
 *   <li>range-checks the three prices within {@code [0.00, 9999999999.99]}, rejecting an
 *       out-of-range value with a {@code 400} naming the field (Requirement 2.5) — a defensive
 *       re-check of the request-level {@code @DecimalMin}/{@code @DecimalMax};</li>
 *   <li>length-checks {@code model}/{@code sku} (≤ 255) and {@code link} (≤ 1024), rejecting a
 *       longer value with a {@code 400} naming the field (Requirements 2.6, 2.7) — a defensive
 *       re-check of the request-level {@code @Size}.</li>
 * </ol>
 * Every rejection throws before persistence, so the enclosing transaction rolls back and nothing is
 * written (Requirements 2.3–2.7). The managed {@code category}/{@code material}/{@code type}/
 * {@code producer}/{@code unit} references and the {@code packages} set are attached to the entity by
 * {@link FinishingMaterialServiceMapper} once normalization has asserted their existence.
 *
 * <p><b>Image orphan cleanup.</b> Following {@code ConstructionMaterialService}, the write path
 * reclaims the previously referenced GCS object on photo replace/detach/delete via
 * {@link ImageStorage#deleteIfOrphan(String)} (Requirement 4.6): {@link #update} deletes the old key
 * when it changed to a different value, {@link #setPropertiesToNull} deletes it when {@code photo} is
 * nulled, and {@link #deleteById} deletes it when the material is removed. Each override captures the
 * previous key, lets the delegate persist and flush the new state, then calls {@code deleteIfOrphan}
 * so the shared reference lookup sees the current DB (a key still referenced elsewhere is kept).
 */
@Service
public class FinishingMaterialService
        implements AdminService<FinishingMaterialServiceModel, FinishingMaterialServiceExtendedModel,
        FinishingMaterialEntity, Long> {

    /** Message code for a missing/dangling reference (404). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** Message code for an empty {@code packages} set (400). */
    static final String PACKAGES_REQUIRED_MESSAGE = "error.finishing.material.packages.required";

    /** Message code for a price outside {@code [0, 9999999999.99]} (400). */
    static final String PRICE_RANGE_MESSAGE = "error.finishing.material.price.range";

    /** Message code for a {@code model}/{@code sku} longer than 255 characters (400). */
    static final String TEXT_LENGTH_MESSAGE = "error.finishing.material.text.length";

    /** Message code for a {@code link} longer than 1024 characters (400). */
    static final String LINK_LENGTH_MESSAGE = "error.finishing.material.link.length";

    /** Inclusive lower bound of a valid price (Requirement 2.5). */
    private static final BigDecimal PRICE_MIN = BigDecimal.ZERO;

    /** Inclusive upper bound of a valid price (Requirement 2.5). */
    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999.99");

    /** Maximum {@code model}/{@code sku} length (Requirement 2.6). */
    private static final int TEXT_MAX_LENGTH = 255;

    /** Maximum {@code link} length (Requirement 2.7). */
    private static final int LINK_MAX_LENGTH = 1024;

    private final FinishingMaterialDao dao;
    private final FinishingMaterialServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ImageStorage imageStorage;
    private final MaterialCategoryDao categoryDao;
    private final MaterialDao materialDao;
    private final MaterialTypeDao typeDao;
    private final MaterialProducerDao producerDao;
    private final MeasurementUnitDao unitDao;
    private final OfferPackageDao offerPackageDao;

    public FinishingMaterialService(FinishingMaterialDao dao,
                                    FinishingMaterialServiceMapper mapper,
                                    AuditLogDao auditLogDao,
                                    EntityManager entityManager,
                                    ImageStorage imageStorage,
                                    MaterialCategoryDao categoryDao,
                                    MaterialDao materialDao,
                                    MaterialTypeDao typeDao,
                                    MaterialProducerDao producerDao,
                                    MeasurementUnitDao unitDao,
                                    OfferPackageDao offerPackageDao) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.imageStorage = imageStorage;
        this.categoryDao = categoryDao;
        this.materialDao = materialDao;
        this.typeDao = typeDao;
        this.producerDao = producerDao;
        this.unitDao = unitDao;
        this.offerPackageDao = offerPackageDao;
    }

    // --- CRUD plumbing ---

    @Override
    public FinishingMaterialDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<FinishingMaterialEntity, FinishingMaterialServiceModel,
            FinishingMaterialServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<FinishingMaterialEntity> getDaoModelClass() {
        return FinishingMaterialEntity.class;
    }

    // --- Pre-persist normalization ---

    @Override
    public void validateCreate(FinishingMaterialServiceExtendedModel model) {
        normalize(model);
    }

    @Override
    public void validateUpdate(FinishingMaterialEntity existing, FinishingMaterialServiceExtendedModel model) {
        normalize(model);
    }

    /**
     * Shared create/update normalization. Resolves-and-validates every reference, rejects an empty
     * package set, and range/length-checks the prices, {@code model}/{@code sku} and {@code link}.
     * Every rejection throws a {@link ForemenApiException} before any write, so the enclosing
     * transaction rolls back with nothing persisted.
     */
    private void normalize(FinishingMaterialServiceExtendedModel model) {
        resolveReferences(model);
        validatePackages(model);
        validatePrices(model);
        validateText(model);
    }

    /**
     * Resolves-and-loads every reference. The mandatory {@code categoryId}/{@code materialId}/
     * {@code unitId} are rejected when null or dangling; the optional {@code typeId}/
     * {@code producerId} allow a null value but reject a non-null dangling id; and every
     * {@code offerPackageId} must resolve to an existing row (Requirements 2.3, 2.4). Existence is
     * asserted with a real load rather than a lazy reference so a dangling id is caught here.
     */
    private void resolveReferences(FinishingMaterialServiceExtendedModel model) {
        requireExisting("categoryId", model.getCategoryId(), id -> categoryDao.findById(id).isPresent());
        requireExisting("materialId", model.getMaterialId(), id -> materialDao.findById(id).isPresent());
        requireExisting("unitId", model.getUnitId(), id -> unitDao.findById(id).isPresent());

        requireExistingIfPresent("typeId", model.getTypeId(), id -> typeDao.findById(id).isPresent());
        requireExistingIfPresent("producerId", model.getProducerId(), id -> producerDao.findById(id).isPresent());

        Set<Long> offerPackageIds = model.getOfferPackageIds();
        if (offerPackageIds != null) {
            for (Long offerPackageId : offerPackageIds) {
                if (offerPackageId == null || offerPackageDao.findById(offerPackageId).isEmpty()) {
                    throw notFound("offerPackageIds", offerPackageId);
                }
            }
        }
    }

    /** Rejects a null or dangling mandatory reference id with a field-identifying 404. */
    private void requireExisting(String field, Long id, java.util.function.LongPredicate exists) {
        if (id == null || !exists.test(id)) {
            throw notFound(field, id);
        }
    }

    /** Allows a null optional reference id, but rejects a non-null dangling one with a 404. */
    private void requireExistingIfPresent(String field, Long id, java.util.function.LongPredicate exists) {
        if (id != null && !exists.test(id)) {
            throw notFound(field, id);
        }
    }

    /**
     * Rejects an empty (or null) {@code offerPackageIds} — a finishing material must carry at least
     * one package (Requirements 2.3, 3.1).
     */
    private void validatePackages(FinishingMaterialServiceExtendedModel model) {
        Set<Long> offerPackageIds = model.getOfferPackageIds();
        if (offerPackageIds == null || offerPackageIds.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PACKAGES_REQUIRED_MESSAGE, "packages");
        }
    }

    /**
     * Range-checks each supplied price within {@code [0.00, 9999999999.99]} (Requirement 2.5) — a
     * defensive re-check of the request-level bean validation.
     */
    private void validatePrices(FinishingMaterialServiceExtendedModel model) {
        validatePrice("purchasePrice", model.getPurchasePrice());
        validatePrice("retailGross", model.getRetailGross());
        validatePrice("retailNet", model.getRetailNet());
    }

    private void validatePrice(String field, BigDecimal value) {
        if (value != null && (value.compareTo(PRICE_MIN) < 0 || value.compareTo(PRICE_MAX) > 0)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PRICE_RANGE_MESSAGE, field);
        }
    }

    /**
     * Length-checks {@code model}/{@code sku} (≤ 255) and {@code link} (≤ 1024) (Requirements 2.6,
     * 2.7) — a defensive re-check of the request-level {@code @Size}.
     */
    private void validateText(FinishingMaterialServiceExtendedModel model) {
        validateLength("model", model.getModel(), TEXT_MAX_LENGTH, TEXT_LENGTH_MESSAGE);
        validateLength("sku", model.getSku(), TEXT_MAX_LENGTH, TEXT_LENGTH_MESSAGE);
        validateLength("link", model.getLink(), LINK_MAX_LENGTH, LINK_LENGTH_MESSAGE);
    }

    private void validateLength(String field, String value, int maxLength, String messageCode) {
        if (value != null && value.length() > maxLength) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, messageCode, field);
        }
    }

    // --- Image orphan cleanup (Requirement 4.6) ---

    /**
     * Updates a material and, when the {@code photo} object key changed to a different value, deletes
     * the previously referenced object if no DB row still references it (photo replace/detach). The
     * previous key is captured before the update; the delegate persists and flushes the new state, so
     * {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public FinishingMaterialServiceExtendedModel update(Long id, FinishingMaterialServiceExtendedModel model) {
        // Capture the previous photo key WITHOUT collapsing a present-but-photoless entity into an
        // empty Optional: Optional.map(getPhoto) returns empty when photo is null, which would
        // spuriously trigger a 404 for a material that exists but simply has no photo. The delegated
        // update below throws NOT_FOUND when the entity is truly absent, so no existence check is
        // needed here — just read the (nullable) photo key.
        FinishingMaterialEntity existing = getReadDao().findById(id).orElse(null);
        String previousKey = existing == null ? null : existing.getPhoto();

        FinishingMaterialServiceExtendedModel result = AdminService.super.update(id, model);

        if (previousKey != null && !Objects.equals(previousKey, model.getPhoto())) {
            imageStorage.deleteIfOrphan(previousKey);
        }
        return result;
    }

    /**
     * Detaches (nulls) the named properties and, when {@code photo} is among them, reclaims the
     * previously referenced object if now unreferenced. The delegate nulls and flushes first, so
     * {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        String previousKey = propertyNames.contains("photo")
                ? getReadDao().findById(id).map(FinishingMaterialEntity::getPhoto).orElse(null)
                : null;

        AdminService.super.setPropertiesToNull(id, propertyNames);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }

    /**
     * Deletes a material and reclaims its photo object, if any, once no DB row references it. The
     * delegate deletes and flushes first, so {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        String previousKey = getReadDao().findById(id)
                .map(FinishingMaterialEntity::getPhoto)
                .orElse(null);

        AdminService.super.deleteById(id);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }

    private ForemenApiException notFound(String field, Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, field, id);
    }

    // ---------------------------------------------------------------------------------------------
    // Audit snapshot override (custom flat snapshot).
    //
    // The generic AdminService audit path serializes the whole FinishingMaterialEntity with the
    // shared audit ObjectMapper. That dumps the nested @ManyToOne / @ManyToMany reference entities
    // (category/material/type/producer/unit/packages) as full nested JSON and risks lazy-init/cycles.
    //
    // Following the ConstructionMaterialService/WorkPriceService pattern, this service overrides ONLY
    // the single-entity serialization seam (serializeEntity) so the inherited create/update/delete
    // transactional bodies run unchanged; serializeUpdateAfterSnapshot is left at the AdminService
    // default (serializeEntity(after)), so before AND after share the same flat shape and the audit
    // UI's generic key-by-key diff works. Nested references are flattened to a simple readable name
    // string (PL name, falling back to RU name then code) rather than nested JSON.
    // ---------------------------------------------------------------------------------------------

    /**
     * Cycle-free, flat single-entity audit snapshot seam. Scalars are emitted verbatim; each nested
     * reference is flattened to a plain readable name (PL name, else RU name, else code), and
     * {@code packages} becomes a flat {@code List<String>} of package names. Used for the CREATE-after,
     * DELETE-before, and (via the inherited {@code serializeUpdateAfterSnapshot} default) both the
     * before and after of an UPDATE.
     */
    @Override
    public String serializeEntity(FinishingMaterialEntity entity) {
        if (entity == null) {
            return null;
        }
        Map<String, Object> snap = buildSnapshot(entity);
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"FinishingMaterialEntity\"}";
        }
    }

    /**
     * Builds the flat, cycle-free snapshot map: scalars plus each nested reference as a simple
     * readable name string (never nested JSON).
     */
    private Map<String, Object> buildSnapshot(FinishingMaterialEntity entity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("model", entity.getModel());
        snap.put("sku", entity.getSku());
        snap.put("features", entity.getFeatures());
        snap.put("purchasePrice", entity.getPurchasePrice());
        snap.put("retailGross", entity.getRetailGross());
        snap.put("retailNet", entity.getRetailNet());
        snap.put("link", entity.getLink());
        snap.put("photo", entity.getPhoto());
        snap.put("active", entity.isActive());

        snap.put("category", categoryName(entity.getCategory()));
        snap.put("material", materialName(entity.getMaterial()));
        snap.put("type", typeName(entity.getType()));
        snap.put("producer", producerName(entity.getProducer()));
        snap.put("unit", unitName(entity.getUnit()));
        snap.put("packages", packageNames(entity.getPackages()));
        return snap;
    }

    private static String categoryName(MaterialCategoryEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static String materialName(MaterialEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static String typeName(MaterialTypeEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static String producerName(MaterialProducerEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static String unitName(MeasurementUnitEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static List<String> packageNames(Set<OfferPackageEntity> packages) {
        if (packages == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (OfferPackageEntity pkg : packages) {
            if (pkg != null) {
                names.add(firstNonBlank(pkg.getNamePL(), pkg.getNameRU(), pkg.getCode()));
            }
        }
        return names;
    }

    /** First non-null, non-blank value among the candidates, or {@code null} when none qualifies. */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
