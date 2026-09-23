package com.foremen.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.foremen.controller.model.PackageAssortmentEditorResponse;
import com.foremen.controller.model.PackageAssortmentSaveRequest;
import com.foremen.dao.AssortmentGroupDao;
import com.foremen.dao.AssortmentPositionDao;
import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
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
    private final OfferPackageDao offerPackageDao;
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
        }

        List<AssortmentGroupEntity> sortedGroups = new ArrayList<>(groupById.values());
        sortedGroups.sort(Comparator
                .comparing(AssortmentGroupEntity::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(g -> displayName(g.getNamePL(), g.getNameRU(), null),
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
                positionDtos.add(new PackageAssortmentEditorResponse.Position(
                        position.getId(),
                        materialType != null ? materialType.getId() : null,
                        materialType != null
                                ? displayName(materialType.getNamePL(), materialType.getNameRU(), null)
                                : null,
                        price != null ? price.getMinPrice() : null,
                        price != null ? price.getAvgPrice() : null,
                        price != null ? price.getMaxPrice() : null,
                        price != null ? price.getMinQty() : null,
                        price != null ? price.getAvgQty() : null,
                        price != null ? price.getMaxQty() : null));
            }
            groupDtos.add(new PackageAssortmentEditorResponse.Group(
                    group.getId(),
                    displayName(group.getNamePL(), group.getNameRU(), null),
                    group.getSortOrder(),
                    group.getReferenceQty(),
                    group.getReferenceUnit(),
                    positionDtos));
        }

        BigDecimal totalMin = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MIN));
        BigDecimal totalAvg = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.AVG));
        BigDecimal totalMax = packageZlM2Resolver.packageZlM2(buildBandContributions(packageCode, PriceField.MAX));

        return new PackageAssortmentEditorResponse(
                packageCode,
                displayName(pkg.getNamePL(), pkg.getNameRU(), pkg.getCode()),
                pkg.getZlM2(),
                totalMin,
                totalAvg,
                totalMax,
                groupDtos);
    }

    /** Repo-wide PL-fallback display name: first non-blank of {@code namePL}, {@code nameRU}, {@code code}. */
    private static String displayName(String namePL, String nameRU, String code) {
        for (String candidate : new String[] {namePL, nameRU, code}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
