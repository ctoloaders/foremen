package com.foremen.service.procurement.position;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.VariantReason;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.mapper.PurchasePositionServiceMapper;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

/**
 * FOR-11-00 (R21): the variant history of a purchase position. The position always holds the current
 * (main) option; the history holds the retired ones.
 *
 * <p>{@link #replace} snapshots the current option into the history and makes the supplied one
 * current; {@link #restore} swaps a history row with the current option. Both write in one
 * transaction, are project-scoped like any by-id position access (out of scope reads as 404), are
 * limited by {@link VariantStagePolicy} and audit the position. Reads apply
 * {@link PositionPriceVisibility} to every price, so the {@code SITE} audience sees none.
 */
@Service
public class PositionVariantService {

    static final String STAGE_NOT_ALLOWED = "error.purchasePosition.variant.stageNotAllowed";
    static final String SAME_AS_CURRENT = "error.purchasePosition.variant.sameAsCurrent";
    static final String VARIANT_NOT_FOUND = "error.purchasePosition.variant.notFound";
    static final String POSITION_NOT_FOUND = "error.purchasePosition.notFound";
    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final String MATERIAL_PROJECT_MISMATCH = "error.purchasePosition.material.projectMismatch";

    public static final int DEFAULT_SUGGESTION_LIMIT = 10;
    public static final int MAX_SUGGESTION_LIMIT = 50;
    /** Rows fetched from each candidate query before merging; bounds the work per request. */
    private static final int CANDIDATE_FETCH = 500;

    private final PurchasePositionService positionService;
    private final PurchasePositionDao positionDao;
    private final PurchasePositionVariantDao variantDao;
    private final FinishingMaterialDao materialDao;
    private final PurchasePositionServiceMapper mapper;
    private final PositionAudienceResolver audienceResolver;

    public PositionVariantService(PurchasePositionService positionService,
                                  PurchasePositionDao positionDao,
                                  PurchasePositionVariantDao variantDao,
                                  FinishingMaterialDao materialDao,
                                  PurchasePositionServiceMapper mapper,
                                  PositionAudienceResolver audienceResolver) {
        this.positionService = positionService;
        this.positionDao = positionDao;
        this.variantDao = variantDao;
        this.materialDao = materialDao;
        this.mapper = mapper;
        this.audienceResolver = audienceResolver;
    }

    // --- Writes ---

    /** Retires the current option into the history (with {@code cmd.reason()}) and sets the new one. */
    @Transactional
    public PurchasePositionServiceExtendedModel replace(Long positionId, ReplaceCommand cmd, Actor actor) {
        if (cmd == null || cmd.materialId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "materialId");
        }
        if (cmd.reason() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "reason");
        }
        PurchasePositionEntity position = loadForWrite(positionId, actor);

        FinishingMaterialEntity target = materialDao.findById(cmd.materialId())
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "materialId", cmd.materialId()));
        if (target.getProject() != null
                && !Objects.equals(target.getProject().getId(), position.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MATERIAL_PROJECT_MISMATCH);
        }

        boolean sameMaterial = Objects.equals(target.getId(), position.getPlannedMaterial().getId());
        BigDecimal newSale = cmd.salePrice() != null ? cmd.salePrice()
                : (sameMaterial ? position.getSalePrice() : null);
        BigDecimal newPurchase = cmd.plannedPurchasePrice() != null ? cmd.plannedPurchasePrice()
                : (sameMaterial ? position.getPlannedPurchasePrice() : null);
        if (sameMaterial && sameAmount(newSale, position.getSalePrice())
                && sameAmount(newPurchase, position.getPlannedPurchasePrice())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SAME_AS_CURRENT);
        }
        if (cmd.salePrice() != null && cmd.salePrice().signum() < 0
                || cmd.plannedPurchasePrice() != null && cmd.plannedPurchasePrice().signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.purchasePosition.price.invalid");
        }

        String before = positionService.serializeEntity(position);
        variantDao.save(snapshot(position, cmd.reason(), cmd.note()));
        position.setPlannedMaterial(target);
        position.setSalePrice(newSale);
        position.setPlannedPurchasePrice(newPurchase);
        return saveAndAudit(position, before);
    }

    /** Makes a history option current; the present current option is retired with reason MANUAL. */
    @Transactional
    public PurchasePositionServiceExtendedModel restore(Long positionId, Long variantId, Actor actor) {
        positionService.assertProjectAccess(positionId);
        PurchasePositionVariantEntity variant = variantDao.findById(variantId)
                .filter(v -> Objects.equals(v.getPosition().getId(), positionId))
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, VARIANT_NOT_FOUND));
        PurchasePositionEntity position = loadForWrite(positionId, actor);

        if (Objects.equals(variant.getMaterial().getId(), position.getPlannedMaterial().getId())
                && sameAmount(variant.getUnitSalePrice(), position.getSalePrice())
                && sameAmount(variant.getUnitPlannedPurchasePrice(), position.getPlannedPurchasePrice())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SAME_AS_CURRENT);
        }

        String before = positionService.serializeEntity(position);
        FinishingMaterialEntity restoredMaterial = variant.getMaterial();
        BigDecimal restoredSale = variant.getUnitSalePrice();
        BigDecimal restoredPurchase = variant.getUnitPlannedPurchasePrice();

        variantDao.save(snapshot(position, VariantReason.MANUAL, null));
        variantDao.delete(variant);
        position.setPlannedMaterial(restoredMaterial);
        position.setSalePrice(restoredSale);
        position.setPlannedPurchasePrice(restoredPurchase);
        return saveAndAudit(position, before);
    }

    // --- Reads ---

    /** The retired options, newest first, money filtered for the caller's audience. */
    @Transactional(readOnly = true)
    public List<VariantView> history(Long positionId) {
        positionService.assertProjectAccess(positionId);
        PurchasePositionEntity position = requirePosition(positionId);
        Audience audience = audienceResolver.current();
        List<VariantView> views = new ArrayList<>();
        for (PurchasePositionVariantEntity v : variantDao.findHistory(positionId)) {
            PositionMoney money = visible(audience, position, v.getUnitSalePrice(), v.getUnitPlannedPurchasePrice());
            FinishingMaterialEntity m = v.getMaterial();
            views.add(new VariantView(v.getId(), positionId, m.getId(), m.getModel(), producerName(m),
                    sellerName(m), m.getLink(), m.getPhoto(), money.salePrice(), money.plannedPurchasePrice(),
                    v.getReason(), v.getNote(), v.getCreatedDate(), v.getCreatedBy()));
        }
        return views;
    }

    /**
     * Known analogs of the position's current material: same project, same material type, taken from
     * the current options and the history of all positions of the project; the latest row per
     * material wins. {@code limit} defaults to 10 and is capped at 50.
     */
    @Transactional(readOnly = true)
    public List<SuggestionView> suggestions(Long positionId, Integer limit) {
        positionService.assertProjectAccess(positionId);
        PurchasePositionEntity position = requirePosition(positionId);
        int max = limit == null ? DEFAULT_SUGGESTION_LIMIT : Math.max(1, Math.min(limit, MAX_SUGGESTION_LIMIT));

        FinishingMaterialEntity current = position.getPlannedMaterial();
        if (current.getType() == null) {
            return List.of();
        }
        Long projectId = position.getProject().getId();
        Long typeId = current.getType().getId();
        PageRequest page = PageRequest.of(0, CANDIDATE_FETCH);

        List<Object[]> rows = new ArrayList<>();
        rows.addAll(positionDao.findSuggestionCandidates(projectId, typeId, current.getId(), page));
        rows.addAll(variantDao.findSuggestionCandidates(projectId, typeId, current.getId(), page));
        rows.sort(Comparator.comparing((Object[] r) -> (LocalDateTime) r[2],
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());

        Map<Long, Object[]> latestPerMaterial = new LinkedHashMap<>();
        for (Object[] row : rows) {
            latestPerMaterial.putIfAbsent((Long) row[0], row);
            if (latestPerMaterial.size() >= max) {
                break;
            }
        }
        if (latestPerMaterial.isEmpty()) {
            return List.of();
        }

        Map<Long, FinishingMaterialEntity> materials = new HashMap<>();
        materialDao.findAllById(latestPerMaterial.keySet()).forEach(m -> materials.put(m.getId(), m));
        Audience audience = audienceResolver.current();
        List<SuggestionView> views = new ArrayList<>();
        for (Object[] row : latestPerMaterial.values()) {
            FinishingMaterialEntity m = materials.get((Long) row[0]);
            if (m == null) {
                continue;
            }
            PositionMoney money = visible(audience, position, (BigDecimal) row[1], null);
            views.add(new SuggestionView(m.getId(), m.getModel(), producerName(m), sellerName(m), m.getLink(),
                    m.getPhoto(), money.salePrice(), (LocalDateTime) row[2]));
        }
        return views;
    }

    // --- Helpers ---

    /** Scope check (404 when out of scope), loads the position and applies the stage rule (409). */
    private PurchasePositionEntity loadForWrite(Long positionId, Actor actor) {
        positionService.assertProjectAccess(positionId);
        PurchasePositionEntity position = requirePosition(positionId);
        if (!VariantStagePolicy.allowed(position.getStatus(), actor)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STAGE_NOT_ALLOWED);
        }
        return position;
    }

    private PurchasePositionEntity requirePosition(Long positionId) {
        return positionDao.findById(positionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, POSITION_NOT_FOUND));
    }

    private PurchasePositionServiceExtendedModel saveAndAudit(PurchasePositionEntity position, String before) {
        PurchasePositionEntity saved = positionDao.save(position);
        positionService.getEntityManager().flush();
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
        PurchasePositionServiceExtendedModel result = mapper.toServiceExtendedModel(saved);
        positionService.fillVariantCounts(List.of(result));
        return result;
    }

    private static PurchasePositionVariantEntity snapshot(PurchasePositionEntity position, VariantReason reason,
                                                          String note) {
        PurchasePositionVariantEntity v = new PurchasePositionVariantEntity();
        v.setPosition(position);
        v.setMaterial(position.getPlannedMaterial());
        v.setUnitSalePrice(position.getSalePrice());
        v.setUnitPlannedPurchasePrice(position.getPlannedPurchasePrice());
        v.setReason(reason);
        v.setNote(note);
        return v;
    }

    private static PositionMoney visible(Audience audience, PurchasePositionEntity position,
                                         BigDecimal salePrice, BigDecimal purchasePrice) {
        PositionMoney raw = new PositionMoney(purchasePrice, null, null, salePrice, null, null);
        return PositionPriceVisibility.apply(audience, position.getSourcingPath(), position.getStatus(), raw);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static boolean russian() {
        return "ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    private static String producerName(FinishingMaterialEntity m) {
        if (m.getProducer() == null) {
            return null;
        }
        return russian() ? m.getProducer().getNameRU() : m.getProducer().getNamePL();
    }

    private static String sellerName(FinishingMaterialEntity m) {
        if (m.getSeller() == null) {
            return null;
        }
        return russian() ? m.getSeller().getNameRU() : m.getSeller().getNamePL();
    }
}
