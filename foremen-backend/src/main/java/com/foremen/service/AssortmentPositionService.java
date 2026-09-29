package com.foremen.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.foremen.controller.model.PackageAssortmentEditorResponse;
import com.foremen.controller.model.PackageAssortmentSaveRequest;
import com.foremen.controller.model.PackageWorkItemsReplaceRequest;
import com.foremen.dao.AssortmentGroupDao;
import com.foremen.dao.AssortmentPositionDao;
import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.AssortmentPositionWorkItemDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.AssortmentPositionWorkItemEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.AssortmentPositionServiceExtendedModel;
import com.foremen.service.model.AssortmentPositionServiceModel;
import com.foremen.service.model.mapper.AssortmentPositionServiceMapper;
import com.foremen.service.pricing.PackageZlM2Resolver;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * CRUD service for {@link AssortmentPositionEntity} (FOR-05-04-UI assortment rework), plus the
 * package zł/m² exposure the downstream FOR-05-06 consumer reads (Requirement 6.3, 6.4, 6.5, 6.8)
 * and the single-package grouped editor read/save flow.
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} — an assortment position is a curated catalog row with no project
 * boundary, mirroring {@code AssortmentGroupService}.
 *
 * <p>The generic CRUD manages positions themselves (creating a global position makes it appear for
 * ALL packages with empty prices until set); the bespoke package-editor/package-save/package-zl-m2
 * flow reads/writes the per-package {@link AssortmentPositionPriceEntity} rows and recomputes
 * {@code offer_packages.zl_m2} on save.
 *
 * <p><b>{@link #computePackageZlM2(String)}.</b> Adapts every currently-persisted
 * {@link AssortmentPositionPriceEntity} into {@link PackageZlM2Resolver.PositionPrice}, grouped by
 * assortment group id (each group carrying its {@link AssortmentGroupEntity#getReferenceQty()}),
 * and delegates to {@link PackageZlM2Resolver#packageZlM2}. The load + adaptation runs fresh on
 * every call — nothing is cached (Requirement 6.5). The group's contribution is
 * {@code (Σ avgPrice) × group.referenceQty / 50}.
 *
 * <p>{@code packageCode} (not {@code offerPackageId}) is the identifier accepted here, matching
 * {@link OfferPackageEntity#getCode()} as the stable, human-readable package identifier.
 */
@Service
@RequiredArgsConstructor
public class AssortmentPositionService implements AdminService<
        AssortmentPositionServiceModel, AssortmentPositionServiceExtendedModel,
        AssortmentPositionEntity, Long> {

    private final AssortmentPositionDao dao;
    private final AssortmentGroupDao groupDao;
    private final AssortmentPositionPriceDao positionPriceDao;
    private final AssortmentPositionWorkItemDao positionWorkItemDao;
    private final OfferPackageDao offerPackageDao;
    private final WorkItemDao workItemDao;
    private final AssortmentPositionServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final PackageZlM2Resolver packageZlM2Resolver;

    @Override
    public AssortmentPositionDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<AssortmentPositionEntity, AssortmentPositionServiceModel,
            AssortmentPositionServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<AssortmentPositionEntity> getDaoModelClass() {
        return AssortmentPositionEntity.class;
    }

    /**
     * Computes the package zł/m² price for {@code packageCode}, recomputed from the current
     * assortment data on every call (Requirement 6.5). The result is the raw zł/m² figure — the
     * downstream FOR-05-06 consumer is responsible for multiplying it by a project's total floor
     * area (Requirement 6.8); this method does not apply that multiplication.
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code} (e.g. {@code "budget"},
     *                    {@code "norm"}, {@code "lux"})
     * @return the package's zł/m² price for {@code packageCode}, rounded to 2 decimals
     */
    @Transactional(readOnly = true)
    public BigDecimal computePackageZlM2(String packageCode) {
        // Headline package zł/m² is the MAX band (FOR-05-04-UI).
        return packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MAX));
    }

    /**
     * Builds the single-package grouped editor read model for {@code packageCode} (FOR-05-04-UI):
     * the package (code, localized name, persisted {@code zlM2}) + the live min/avg/max TOTAL band
     * + every assortment group (sorted by {@code sortOrder} then localized name) with its
     * {@code referenceQty}/{@code referenceUnit} and ALL of the group's global positions, each
     * carrying THIS package's prices (null when no price row yet).
     */
    @Transactional(readOnly = true)
    public PackageAssortmentEditorResponse buildEditor(String packageCode) {
        OfferPackageEntity pkg = requirePackage(packageCode);
        return buildEditorResponse(pkg);
    }

    /**
     * Persists an edited single-package assortment in ONE transaction (FOR-05-04-UI): updates each
     * group's {@code referenceQty}/{@code referenceUnit}; upserts each position's min/avg/max price
     * row for THIS package (creating the row if absent, else updating it); recomputes the package
     * avg zł/m² via {@link PackageZlM2Resolver} and stores it into {@code offer_packages.zl_m2};
     * and returns the refreshed editor response.
     *
     * <p>Validation (prices ≥ 0, {@code referenceQty > 0}, {@code referenceUnit ∈ {szt, m2}}) is
     * enforced by the request DTO constraints and re-checked here defensively.
     */
    @Transactional
    public PackageAssortmentEditorResponse savePackage(String packageCode, PackageAssortmentSaveRequest request) {
        OfferPackageEntity pkg = requirePackage(packageCode);

        // Index this package's existing price rows by position id, so edits upsert per position.
        Map<Long, AssortmentPositionPriceEntity> pricesByPosition = new HashMap<>();
        for (AssortmentPositionPriceEntity price : positionPriceDao.findAll()) {
            if (price.getOfferPackage() != null && packageCode.equals(price.getOfferPackage().getCode())
                    && price.getPosition() != null) {
                pricesByPosition.put(price.getPosition().getId(), price);
            }
        }

        for (PackageAssortmentSaveRequest.Group group : request.groups()) {
            applyGroupEdit(group, pkg, pricesByPosition);
        }

        entityManager.flush();

        // Headline package zł/m² is the MAX band (FOR-05-04-UI).
        BigDecimal zlM2 = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MAX));
        pkg.setZlM2(zlM2);

        return buildEditorResponse(pkg);
    }

    /**
     * Clears ONE band's quantity override for a position under {@code packageCode}, setting the
     * matching {@code assortment_position_prices.<band>_qty} column to {@code NULL} in place
     * (FOR-05-04-UI). This is a dedicated, immediate operation — NOT part of the batched save —
     * because with {@code 0} now a legitimate stored override, only {@code null} can mean "not
     * overridden", and the batched save cannot express a clear. After nulling, the package MAX
     * zł/m² is recomputed and persisted, and the refreshed editor is returned.
     *
     * <p>A no-op (returns the current editor unchanged) when no price row exists yet for the
     * (position, package) pair — there is no override to clear.
     *
     * @param packageCode the target {@code OfferPackage} code
     * @param positionId  the assortment position whose band override to clear
     * @param band        which band's qty override to null ({@code "min"}/{@code "avg"}/{@code "max"})
     */
    @Transactional
    public PackageAssortmentEditorResponse clearPositionQty(String packageCode, Long positionId, String band) {
        OfferPackageEntity pkg = requirePackage(packageCode);
        PriceField field = parseBand(band);

        AssortmentPositionPriceEntity price = null;
        for (AssortmentPositionPriceEntity candidate : positionPriceDao.findAll()) {
            if (candidate.getOfferPackage() != null
                    && packageCode.equals(candidate.getOfferPackage().getCode())
                    && candidate.getPosition() != null
                    && positionId.equals(candidate.getPosition().getId())) {
                price = candidate;
                break;
            }
        }

        if (price != null) {
            switch (field) {
                case MIN -> price.setMinQty(null);
                case AVG -> price.setAvgQty(null);
                case MAX -> price.setMaxQty(null);
            }
            entityManager.flush();

            BigDecimal zlM2 = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MAX));
            pkg.setZlM2(zlM2);
        }

        return buildEditorResponse(pkg);
    }

    /**
     * Replaces a position's PER-package work-item links (FOR-05-05 Wave 1b, #8) with the supplied
     * full set, in ONE transaction: for each item, a {@code null} {@code workItemId} DELETES the
     * link for that package (if any), otherwise the link is UPSERTED (created or its work item
     * updated). Packages NOT named in the request are left untouched, so the request expresses a
     * targeted replacement (which also covers a client-side "propagate to all packages" — the client
     * simply sends the propagated set).
     *
     * <p>Guarded at the controller by {@code PACKAGE_ASSORTMENT UPDATE} (mirroring the package-save
     * flow). Returns the refreshed per-package links for the position (one entry per active editor
     * package, null work when unlinked).
     *
     * @param positionId the assortment position whose links to replace
     * @param request    the full set of {@code (packageCode, workItemId|null)} items to apply
     * @return the refreshed per-package links for the position after the replacement
     */
    @Transactional
    public List<PackageAssortmentEditorResponse.PackageWorkItem> replacePackageWorkItems(
            Long positionId, PackageWorkItemsReplaceRequest request) {
        AssortmentPositionEntity position = entityManager.find(AssortmentPositionEntity.class, positionId);
        if (position == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Assortment position not found: " + positionId);
        }

        // Existing links for this position, keyed by package code, so we upsert/delete in place.
        Map<String, AssortmentPositionWorkItemEntity> existingByPackageCode = new HashMap<>();
        for (AssortmentPositionWorkItemEntity link : positionWorkItemDao.findByPosition_Id(positionId)) {
            if (link.getOfferPackage() != null && link.getOfferPackage().getCode() != null) {
                existingByPackageCode.put(link.getOfferPackage().getCode(), link);
            }
        }

        if (request.items() != null) {
            for (PackageWorkItemsReplaceRequest.Item item : request.items()) {
                applyPackageWorkItem(position, item, existingByPackageCode);
            }
        }
        entityManager.flush();

        return refreshedPackageWorkItems(positionId);
    }

    /**
     * Applies one {@code (packageCode, workItemId|null)} item to {@code position}: resolves the
     * package (404 when unknown); a {@code null} work id DELETES the existing link for that package
     * (a no-op when absent); a non-null work id UPSERTS the link (404 when the work is unknown),
     * updating an existing row's work or creating a new one.
     */
    private void applyPackageWorkItem(AssortmentPositionEntity position,
                                      PackageWorkItemsReplaceRequest.Item item,
                                      Map<String, AssortmentPositionWorkItemEntity> existingByPackageCode) {
        String packageCode = item.packageCode();
        OfferPackageEntity pkg = requirePackage(packageCode);
        AssortmentPositionWorkItemEntity existing = existingByPackageCode.get(packageCode);

        if (item.workItemId() == null) {
            if (existing != null) {
                positionWorkItemDao.delete(existing);
                existingByPackageCode.remove(packageCode);
            }
            return;
        }

        WorkItemEntity workItem = entityManager.find(WorkItemEntity.class, item.workItemId());
        if (workItem == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Work item not found: " + item.workItemId());
        }
        if (existing != null) {
            existing.setWorkItem(workItem);
        } else {
            AssortmentPositionWorkItemEntity link = new AssortmentPositionWorkItemEntity();
            link.setPosition(position);
            link.setOfferPackage(pkg);
            link.setWorkItem(workItem);
            positionWorkItemDao.save(link);
            existingByPackageCode.put(packageCode, link);
        }
    }

    /**
     * The refreshed per-package links for {@code positionId} after a write: one entry per ACTIVE
     * offer package (order_no then code), the linked work (id + localized name) or null when
     * unlinked — the same shape the editor Position carries.
     */
    private List<PackageAssortmentEditorResponse.PackageWorkItem> refreshedPackageWorkItems(Long positionId) {
        boolean ru = isRussianLocale();

        Map<String, WorkItemEntity> workByPackageCode = new HashMap<>();
        for (AssortmentPositionWorkItemEntity link : positionWorkItemDao.findByPosition_Id(positionId)) {
            if (link.getOfferPackage() != null && link.getOfferPackage().getCode() != null) {
                workByPackageCode.put(link.getOfferPackage().getCode(), link.getWorkItem());
            }
        }

        List<OfferPackageEntity> activePackages = new ArrayList<>();
        for (OfferPackageEntity op : offerPackageDao.findAll()) {
            if (op.isActive()) {
                activePackages.add(op);
            }
        }
        activePackages.sort(Comparator
                .comparing(OfferPackageEntity::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OfferPackageEntity::getCode, Comparator.nullsLast(Comparator.naturalOrder())));

        List<PackageAssortmentEditorResponse.PackageWorkItem> out = new ArrayList<>(activePackages.size());
        for (OfferPackageEntity op : activePackages) {
            WorkItemEntity workItem = workByPackageCode.get(op.getCode());
            out.add(new PackageAssortmentEditorResponse.PackageWorkItem(
                    op.getCode(),
                    workItem != null ? workItem.getId() : null,
                    workItem != null
                            ? localizedName(ru, workItem.getNameRU(), workItem.getNamePL(), null)
                            : null));
        }
        return out;
    }

    /** A {@code (positionId, packageCode)} key for indexing per-package work links. */
    private record PositionPackageKey(Long positionId, String packageCode) {
    }

    private static PriceField parseBand(String band) {
        if (band == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "band is required");
        }
        return switch (band) {
            case "min" -> PriceField.MIN;
            case "avg" -> PriceField.AVG;
            case "max" -> PriceField.MAX;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "band must be one of {min, avg, max}");
        };
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Which price/qty band a resolver contribution carries when building resolver inputs. */
    private enum PriceField { MIN, AVG, MAX }

    /**
     * Adapts every currently-persisted price row for {@code packageCode} into a flat list of
     * {@link PackageZlM2Resolver.Contribution}s for one band: each carries the band's price and the
     * EFFECTIVE quantity — the row's per-band override when set, else the owning group's
     * {@code referenceQty}. Rows for other packages are skipped.
     */
    private List<PackageZlM2Resolver.Contribution> buildBandContributions(String packageCode, PriceField band) {
        List<PackageZlM2Resolver.Contribution> contributions = new ArrayList<>();
        for (AssortmentPositionPriceEntity price : positionPriceDao.findAll()) {
            AssortmentPositionEntity position = price.getPosition();
            if (position == null || position.getGroup() == null || price.getOfferPackage() == null) {
                continue;
            }
            if (!packageCode.equals(price.getOfferPackage().getCode())) {
                continue;
            }
            BigDecimal groupRefQty = position.getGroup().getReferenceQty();
            contributions.add(new PackageZlM2Resolver.Contribution(
                    priceOf(price, band),
                    effectiveQty(price, band, groupRefQty)));
        }
        return contributions;
    }

    private static BigDecimal priceOf(AssortmentPositionPriceEntity price, PriceField band) {
        return switch (band) {
            case MIN -> price.getMinPrice();
            case AVG -> price.getAvgPrice();
            case MAX -> price.getMaxPrice();
        };
    }

    /** The band's override quantity when set, else the group's reference qty (the default). */
    private static BigDecimal effectiveQty(AssortmentPositionPriceEntity price, PriceField band,
                                           BigDecimal groupRefQty) {
        BigDecimal override = switch (band) {
            case MIN -> price.getMinQty();
            case AVG -> price.getAvgQty();
            case MAX -> price.getMaxQty();
        };
        return override != null ? override : groupRefQty;
    }

    private OfferPackageEntity requirePackage(String packageCode) {
        return offerPackageDao.findByCode(packageCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Offer package not found: " + packageCode));
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be >= 0");
        }
    }

    private static void requireNonNegativeIfPresent(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be >= 0 when set");
        }
    }

    /**
     * Applies one group's edit within the save transaction: validates and writes the group's
     * {@code referenceQty}/{@code referenceUnit}, then upserts each of its position price rows for
     * the current package.
     */
    private void applyGroupEdit(PackageAssortmentSaveRequest.Group group,
                                OfferPackageEntity pkg,
                                Map<Long, AssortmentPositionPriceEntity> pricesByPosition) {
        BigDecimal referenceQty = group.referenceQty();
        if (referenceQty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "referenceQty must be > 0");
        }
        String referenceUnit = group.referenceUnit();
        if (!"szt".equals(referenceUnit) && !"m2".equals(referenceUnit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "referenceUnit must be one of {szt, m2}");
        }

        AssortmentGroupEntity groupEntity = entityManager.find(AssortmentGroupEntity.class, group.groupId());
        if (groupEntity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Assortment group not found: " + group.groupId());
        }
        groupEntity.setReferenceQty(referenceQty);
        groupEntity.setReferenceUnit(referenceUnit);

        if (group.positions() == null) {
            return;
        }
        for (PackageAssortmentSaveRequest.Position position : group.positions()) {
            applyPositionEdit(position, pkg, pricesByPosition);
        }
    }

    /**
     * Upserts one position's price row for the current package: validates prices, then updates the
     * existing row or creates a new {@link AssortmentPositionPriceEntity} when none exists yet.
     */
    private void applyPositionEdit(PackageAssortmentSaveRequest.Position position,
                                   OfferPackageEntity pkg,
                                   Map<Long, AssortmentPositionPriceEntity> pricesByPosition) {
        requireNonNegative(position.minPrice(), "minPrice");
        requireNonNegative(position.avgPrice(), "avgPrice");
        requireNonNegative(position.maxPrice(), "maxPrice");
        requireNonNegativeIfPresent(position.minQty(), "minQty");
        requireNonNegativeIfPresent(position.avgQty(), "avgQty");
        requireNonNegativeIfPresent(position.maxQty(), "maxQty");

        AssortmentPositionPriceEntity price = pricesByPosition.get(position.positionId());
        if (price == null) {
            AssortmentPositionEntity positionEntity =
                    entityManager.find(AssortmentPositionEntity.class, position.positionId());
            if (positionEntity == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Assortment position not found: " + position.positionId());
            }
            price = new AssortmentPositionPriceEntity();
            price.setPosition(positionEntity);
            price.setOfferPackage(pkg);
            entityManager.persist(price);
            pricesByPosition.put(position.positionId(), price);
        }
        price.setMinPrice(position.minPrice());
        price.setAvgPrice(position.avgPrice());
        price.setMaxPrice(position.maxPrice());
        // Per-band quantity overrides: null clears the override (falls back to the group refQty).
        price.setMinQty(position.minQty());
        price.setAvgQty(position.avgQty());
        price.setMaxQty(position.maxQty());
    }

    /**
     * Assembles the editor response for {@code pkg} from the current, freshly-read assortment data:
     * every group (sorted by {@code sortOrder} then localized name), ALL of each group's global
     * positions (each carrying this package's prices, null when no row yet), and the live
     * min/avg/max TOTAL band plus the persisted {@code zlM2}.
     */
    private PackageAssortmentEditorResponse buildEditorResponse(OfferPackageEntity pkg) {
        boolean ru = isRussianLocale();
        String packageCode = pkg.getCode();

        // This package's price rows indexed by position id.
        Map<Long, AssortmentPositionPriceEntity> priceByPosition = new HashMap<>();
        for (AssortmentPositionPriceEntity price : positionPriceDao.findAll()) {
            if (price.getOfferPackage() != null && packageCode.equals(price.getOfferPackage().getCode())
                    && price.getPosition() != null) {
                priceByPosition.put(price.getPosition().getId(), price);
            }
        }

        // Group id -> group entity + ALL its positions (global), in load order. Seed EVERY group
        // first (including ones with no positions yet) so a freshly created empty group still
        // appears in the editor — otherwise positions cannot be added to it. Positions are then
        // bucketed onto their group.
        Map<Long, AssortmentGroupEntity> groupById = new LinkedHashMap<>();
        Map<Long, List<AssortmentPositionEntity>> positionsByGroup = new HashMap<>();
        List<Long> allPositionIds = new ArrayList<>();
        for (AssortmentGroupEntity group : groupDao.findAll()) {
            if (group.getId() != null) {
                groupById.putIfAbsent(group.getId(), group);
            }
        }
        for (AssortmentPositionEntity position : dao.findAll()) {
            AssortmentGroupEntity group = position.getGroup();
            if (group == null) {
                continue;
            }
            groupById.putIfAbsent(group.getId(), group);
            positionsByGroup.computeIfAbsent(group.getId(), id -> new ArrayList<>()).add(position);
            if (position.getId() != null) {
                allPositionIds.add(position.getId());
            }
        }

        // The offer packages the per-package work-item dropdowns are rendered for (FOR-05-05 Wave 1b,
        // #8): every ACTIVE package, in a stable order (order_no then code), so each position emits
        // one PackageWorkItem entry per package (null work when unlinked for that package).
        List<OfferPackageEntity> editorPackages = new ArrayList<>();
        for (OfferPackageEntity op : offerPackageDao.findAll()) {
            if (op.isActive()) {
                editorPackages.add(op);
            }
        }
        editorPackages.sort(Comparator
                .comparing(OfferPackageEntity::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OfferPackageEntity::getCode, Comparator.nullsLast(Comparator.naturalOrder())));

        // Batch-load the per-package work links for ALL positions once (no N+1): keyed by
        // (positionId, packageCode) -> work item.
        Map<PositionPackageKey, WorkItemEntity> workByPositionPackage = new HashMap<>();
        List<AssortmentPositionWorkItemEntity> links = allPositionIds.isEmpty()
                ? List.of()
                : positionWorkItemDao.findByPosition_IdIn(allPositionIds);
        for (AssortmentPositionWorkItemEntity link : links) {
            if (link.getPosition() == null || link.getPosition().getId() == null
                    || link.getOfferPackage() == null || link.getOfferPackage().getCode() == null) {
                continue;
            }
            workByPositionPackage.put(
                    new PositionPackageKey(link.getPosition().getId(), link.getOfferPackage().getCode()),
                    link.getWorkItem());
        }

        List<AssortmentGroupEntity> sortedGroups = new ArrayList<>(groupById.values());
        sortedGroups.sort(Comparator
                .comparing(AssortmentGroupEntity::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(g -> localizedName(ru, g.getNameRU(), g.getNamePL(), null),
                        Comparator.nullsLast(Comparator.naturalOrder())));

        List<PackageAssortmentEditorResponse.Group> groupDtos = new ArrayList<>();
        for (AssortmentGroupEntity group : sortedGroups) {
            List<AssortmentPositionEntity> positions = new ArrayList<>(
                    positionsByGroup.getOrDefault(group.getId(), List.of()));
            positions.sort(Comparator
                    .comparing(AssortmentPositionEntity::getSortOrder,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(AssortmentPositionEntity::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())));

            List<PackageAssortmentEditorResponse.Position> positionDtos = new ArrayList<>();
            for (AssortmentPositionEntity position : positions) {
                MaterialTypeEntity materialType = position.getMaterialType();
                AssortmentPositionPriceEntity price = priceByPosition.get(position.getId());
                // One PackageWorkItem entry per editor package (FOR-05-05 Wave 1b, #8): the linked
                // work for that package, or null work when unlinked. The UI renders a dropdown per
                // package from these entries.
                List<PackageAssortmentEditorResponse.PackageWorkItem> packageWorkItems =
                        new ArrayList<>(editorPackages.size());
                for (OfferPackageEntity op : editorPackages) {
                    WorkItemEntity workItem =
                            workByPositionPackage.get(new PositionPackageKey(position.getId(), op.getCode()));
                    packageWorkItems.add(new PackageAssortmentEditorResponse.PackageWorkItem(
                            op.getCode(),
                            workItem != null ? workItem.getId() : null,
                            workItem != null
                                    ? localizedName(ru, workItem.getNameRU(), workItem.getNamePL(), null)
                                    : null));
                }
                positionDtos.add(new PackageAssortmentEditorResponse.Position(
                        position.getId(),
                        materialType != null ? materialType.getId() : null,
                        materialType != null
                                ? localizedName(ru, materialType.getNameRU(), materialType.getNamePL(), null)
                                : null,
                        price != null ? price.getMinPrice() : null,
                        price != null ? price.getAvgPrice() : null,
                        price != null ? price.getMaxPrice() : null,
                        price != null ? price.getMinQty() : null,
                        price != null ? price.getAvgQty() : null,
                        price != null ? price.getMaxQty() : null,
                        packageWorkItems));
            }
            groupDtos.add(new PackageAssortmentEditorResponse.Group(
                    group.getId(),
                    localizedName(ru, group.getNameRU(), group.getNamePL(), null),
                    group.getSortOrder(),
                    group.getReferenceQty(),
                    group.getReferenceUnit(),
                    sortedRoomTypeIds(group),
                    positionDtos));
        }

        BigDecimal totalMin = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MIN));
        BigDecimal totalAvg = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.AVG));
        BigDecimal totalMax = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MAX));

        return new PackageAssortmentEditorResponse(
                packageCode,
                localizedName(ru, pkg.getNameRU(), pkg.getNamePL(), pkg.getCode()),
                pkg.getZlM2(),
                totalMin,
                totalAvg,
                totalMax,
                groupDtos);
    }

    /**
     * The group's applicable room-type ids (FOR-05-05 Amendment A1), sorted ascending. Read inside
     * the {@code readOnly} editor transaction, so the LAZY {@code roomTypes} set is initialized here.
     */
    private static List<Long> sortedRoomTypeIds(AssortmentGroupEntity group) {
        List<Long> ids = new ArrayList<>();
        for (RoomTypeEntity roomType : group.getRoomTypes()) {
            if (roomType != null && roomType.getId() != null) {
                ids.add(roomType.getId());
            }
        }
        ids.sort(Long::compareTo);
        return ids;
    }

    /**
     * Whether the current request locale is Russian. Mirrors the repo-wide convention
     * ({@code EstimateMatrixAssembler}, {@code RoleService}, {@code ProjectService}).
     */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * Locale-aware display name: the locale-preferred name first (RU when {@code ru}, else PL), then
     * the OTHER locale's name, then {@code fallbackCode} — each only when non-blank. So a missing
     * RU name still falls back to PL, and blanks never win over a present candidate. Returns
     * {@code null} only when every candidate is null/blank.
     */
    private static String localizedName(boolean ru, String nameRU, String namePL, String fallbackCode) {
        String preferred = ru ? nameRU : namePL;
        String other = ru ? namePL : nameRU;
        for (String candidate : new String[] {preferred, other, fallbackCode}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
