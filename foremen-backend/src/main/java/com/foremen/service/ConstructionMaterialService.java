package com.foremen.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialServiceModel;
import com.foremen.service.model.mapper.ConstructionMaterialServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * CRUD service for {@link ConstructionMaterialEntity} (FOR-04-17, task 5.2).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} (Requirement 4.10) — construction materials are catalog rows with no
 * project boundary, so no {@code getProjectIdPath()} is supplied.
 *
 * <p><b>Pre-persist normalization.</b> Both create and update run {@link #normalize} through the
 * {@link #validateCreate}/{@link #validateUpdate} hooks (mirroring {@code RoomService}). Fired inside
 * the generic {@link AdminService} create/update transaction <em>before</em> the mapper turns the
 * model into (or onto) the entity, normalization:
 * <ol>
 *   <li>resolves-and-loads every reference — mandatory {@code typeId}/{@code unitId}/
 *       {@code currencyId} and optional {@code producerId}/{@code sellerId} — rejecting a
 *       null-or-dangling mandatory id, or a non-null dangling optional id, with a field-identifying
 *       {@code 404 error.entity.not.found} (Requirements 4.3, 4.4);</li>
 *   <li>range-checks the three prices within {@code [0.00, 9999999999.99]}, rejecting an
 *       out-of-range value with a {@code 400} naming the field (Requirement 4.5) — a defensive
 *       re-check of the request-level {@code @DecimalMin}/{@code @DecimalMax};</li>
 *   <li>length-checks {@code website} (≤ 255), rejecting a longer value with a {@code 400} naming
 *       {@code website} (Requirement 4.6) — a defensive re-check of the request-level
 *       {@code @Size}.</li>
 * </ol>
 * Every rejection throws before persistence, so the enclosing transaction rolls back and nothing is
 * written (Requirements 4.3–4.6). The managed {@code type}/{@code producer}/{@code seller}/
 * {@code unit}/{@code currency} references are attached to the entity by
 * {@link ConstructionMaterialServiceMapper} once normalization has asserted their existence.
 *
 * <p><b>Image orphan cleanup.</b> Following {@code MaterialProducerService}, the write path reclaims
 * the previously referenced GCS object on image replace/detach/delete via
 * {@link ImageStorage#deleteIfOrphan(String)} (Requirements 7.8, 7.9, 4.7): {@link #update} deletes
 * the old key when it changed to a different value, {@link #setPropertiesToNull} deletes it when
 * {@code image} is nulled, and {@link #deleteById} deletes it when the material is removed. Each
 * override captures the previous key, lets the delegate persist and flush the new state, then calls
 * {@code deleteIfOrphan} so the shared reference lookup sees the current DB (a key still referenced
 * elsewhere is kept).
 */
@Service
public class ConstructionMaterialService
        implements AdminService<ConstructionMaterialServiceModel, ConstructionMaterialServiceExtendedModel,
        ConstructionMaterialEntity, Long> {

    /** Message code for a missing/dangling reference (404). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** Message code for a price outside {@code [0, 9999999999.99]} (400). */
    static final String PRICE_RANGE_MESSAGE = "error.construction.material.price.range";

    /** Message code for a {@code website} longer than 255 characters (400). */
    static final String WEBSITE_LENGTH_MESSAGE = "error.construction.material.website.length";

    /** Inclusive lower bound of a valid price (Requirement 4.5). */
    private static final BigDecimal PRICE_MIN = BigDecimal.ZERO;

    /** Inclusive upper bound of a valid price (Requirement 4.5). */
    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999.99");

    /** Maximum {@code website} length (Requirement 4.6). */
    private static final int WEBSITE_MAX_LENGTH = 255;

    private final ConstructionMaterialDao dao;
    private final ConstructionMaterialServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ImageStorage imageStorage;
    private final ConstructionMaterialTypeDao typeDao;
    private final MaterialProducerDao producerDao;
    private final MaterialSellerDao sellerDao;
    private final MeasurementUnitDao unitDao;
    private final CurrencyDao currencyDao;

    public ConstructionMaterialService(ConstructionMaterialDao dao,
                                       ConstructionMaterialServiceMapper mapper,
                                       AuditLogDao auditLogDao,
                                       EntityManager entityManager,
                                       ImageStorage imageStorage,
                                       ConstructionMaterialTypeDao typeDao,
                                       MaterialProducerDao producerDao,
                                       MaterialSellerDao sellerDao,
                                       MeasurementUnitDao unitDao,
                                       CurrencyDao currencyDao) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.imageStorage = imageStorage;
        this.typeDao = typeDao;
        this.producerDao = producerDao;
        this.sellerDao = sellerDao;
        this.unitDao = unitDao;
        this.currencyDao = currencyDao;
    }

    // --- CRUD plumbing ---

    @Override
    public ConstructionMaterialDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ConstructionMaterialEntity, ConstructionMaterialServiceModel,
            ConstructionMaterialServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ConstructionMaterialEntity> getDaoModelClass() {
        return ConstructionMaterialEntity.class;
    }

    // --- Pre-persist normalization ---

    @Override
    public void validateCreate(ConstructionMaterialServiceExtendedModel model) {
        normalize(model);
    }

    @Override
    public void validateUpdate(ConstructionMaterialEntity existing, ConstructionMaterialServiceExtendedModel model) {
        normalize(model);
    }

    /**
     * Shared create/update normalization. Resolves-and-validates every reference and range/length-
     * checks the prices and {@code website}. Every rejection throws a {@link ForemenApiException}
     * before any write, so the enclosing transaction rolls back with nothing persisted.
     */
    private void normalize(ConstructionMaterialServiceExtendedModel model) {
        resolveReferences(model);
        validatePrices(model);
        validateWebsite(model);
    }

    /**
     * Resolves-and-loads every reference. The mandatory {@code typeId}/{@code unitId}/
     * {@code currencyId} are rejected when null or dangling; the optional {@code producerId}/
     * {@code sellerId} allow a null value but reject a non-null dangling id (Requirements 4.3, 4.4).
     * Existence is asserted with a real load rather than a lazy reference so a dangling id is caught
     * here.
     */
    private void resolveReferences(ConstructionMaterialServiceExtendedModel model) {
        requireExisting("typeId", model.getTypeId(), id -> typeDao.findById(id).isPresent());
        requireExisting("unitId", model.getUnitId(), id -> unitDao.findById(id).isPresent());
        requireExisting("currencyId", model.getCurrencyId(), id -> currencyDao.findById(id).isPresent());

        requireExistingIfPresent("producerId", model.getProducerId(), id -> producerDao.findById(id).isPresent());
        requireExistingIfPresent("sellerId", model.getSellerId(), id -> sellerDao.findById(id).isPresent());
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
     * Range-checks each supplied price within {@code [0.00, 9999999999.99]} (Requirement 4.5) — a
     * defensive re-check of the request-level bean validation.
     */
    private void validatePrices(ConstructionMaterialServiceExtendedModel model) {
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
     * Length-checks {@code website} (≤ 255) (Requirement 4.6) — a defensive re-check of the
     * request-level {@code @Size}.
     */
    private void validateWebsite(ConstructionMaterialServiceExtendedModel model) {
        String website = model.getWebsite();
        if (website != null && website.length() > WEBSITE_MAX_LENGTH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, WEBSITE_LENGTH_MESSAGE, "website");
        }
    }

    // --- Image orphan cleanup (Requirements 7.8, 7.9, 4.7) ---

    /**
     * Updates a material and, when the {@code image} object key changed to a different value, deletes
     * the previously referenced object if no DB row still references it (image replace/detach). The
     * previous key is captured before the update; the delegate persists and flushes the new state, so
     * {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public ConstructionMaterialServiceExtendedModel update(Long id, ConstructionMaterialServiceExtendedModel model) {
        // Capture the previous image key WITHOUT collapsing a present-but-imageless entity into an
        // empty Optional: Optional.map(getImage) returns empty when image is null, which would
        // spuriously trigger a 404 for a material that exists but simply has no image. The delegated
        // update below throws NOT_FOUND when the entity is truly absent, so no existence check is
        // needed here — just read the (nullable) image key.
        ConstructionMaterialEntity existing = getReadDao().findById(id).orElse(null);
        String previousKey = existing == null ? null : existing.getImage();

        ConstructionMaterialServiceExtendedModel result = AdminService.super.update(id, model);

        if (previousKey != null && !Objects.equals(previousKey, model.getImage())) {
            imageStorage.deleteIfOrphan(previousKey);
        }
        return result;
    }

    /**
     * Detaches (nulls) the named properties and, when {@code image} is among them, reclaims the
     * previously referenced object if now unreferenced. The delegate nulls and flushes first, so
     * {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        String previousKey = propertyNames.contains("image")
                ? getReadDao().findById(id).map(ConstructionMaterialEntity::getImage).orElse(null)
                : null;

        AdminService.super.setPropertiesToNull(id, propertyNames);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }

    /**
     * Deletes a material and reclaims its image object, if any, once no DB row references it. The
     * delegate deletes and flushes first, so {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        String previousKey = getReadDao().findById(id)
                .map(ConstructionMaterialEntity::getImage)
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
    // The generic AdminService audit path serializes the whole ConstructionMaterialEntity with the
    // shared audit ObjectMapper. That dumps the nested @ManyToOne reference entities
    // (type/producer/seller/unit/currency) as full nested JSON and risks lazy-init/cycles.
    //
    // Following the WorkPriceService pattern, this service overrides ONLY the single-entity
    // serialization seam (serializeEntity) so the inherited create/update/delete transactional bodies
    // run unchanged; serializeUpdateAfterSnapshot is left at the AdminService default
    // (serializeEntity(after)), so before AND after share the same flat shape and the audit UI's
    // generic key-by-key diff works. Nested references are flattened to a simple readable name string
    // (PL name, falling back to RU name then code) rather than nested JSON.
    // ---------------------------------------------------------------------------------------------

    /**
     * Cycle-free, flat single-entity audit snapshot seam. Scalars are emitted verbatim; each nested
     * reference is flattened to a plain readable name (PL name, else RU name, else code). Used for
     * the CREATE-after, DELETE-before, and (via the inherited {@code serializeUpdateAfterSnapshot}
     * default) both the before and after of an UPDATE.
     */
    @Override
    public String serializeEntity(ConstructionMaterialEntity entity) {
        if (entity == null) {
            return null;
        }
        Map<String, Object> snap = buildSnapshot(entity);
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"ConstructionMaterialEntity\"}";
        }
    }

    /**
     * Builds the flat, cycle-free snapshot map: scalars plus each nested reference as a simple
     * readable name string (never nested JSON).
     */
    private Map<String, Object> buildSnapshot(ConstructionMaterialEntity entity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("nameRU", entity.getNameRU());
        snap.put("namePL", entity.getNamePL());
        snap.put("purchasePrice", entity.getPurchasePrice());
        snap.put("retailGross", entity.getRetailGross());
        snap.put("retailNet", entity.getRetailNet());
        snap.put("website", entity.getWebsite());
        snap.put("image", entity.getImage());
        snap.put("active", entity.isActive());

        snap.put("type", typeName(entity.getType()));
        snap.put("producer", producerName(entity.getProducer()));
        snap.put("seller", sellerName(entity.getSeller()));
        snap.put("unit", unitName(entity.getUnit()));
        snap.put("currency", currencyName(entity.getCurrency()));
        return snap;
    }

    private static String typeName(ConstructionMaterialTypeEntity e) {
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

    private static String sellerName(MaterialSellerEntity e) {
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

    private static String currencyName(CurrencyEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getCode(), e.getNamePL(), e.getNameRU());
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
