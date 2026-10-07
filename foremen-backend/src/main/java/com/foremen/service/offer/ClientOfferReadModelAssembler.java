package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.service.image.ImageStorage;

/**
 * Assembles the offer read models served to callers (FOR-05-07, Requirements 7.4, 15.1, 15.2,
 * 19.3). It is the <b>only</b> path that turns an {@link OfferEntity} into a client-facing payload,
 * and by construction it maps ONLY offer-level fields into the {@link ClientOfferReadModel} graph —
 * it never reads or maps a self-cost, cost, margin, worker rate, or estimate-internal unit price, so
 * the client type graph is structurally free of confidential fields (Property 21).
 *
 * <p><b>Skeleton (task 2.3).</b> This wires the DTO/assembler graph so it compiles and the offer-level
 * fields available directly on the entity (status, derived visibility, revision, selected-package
 * code, the derived totals cache, applied discounts, the negotiation thread) are mapped. The
 * price-derivation collaborators — the live-referenced estimate client-facing prices feeding
 * {@link PerLineOfferPrice}/{@link PerCategoryOfferPrice}/{@link PackagePriceView} and the
 * {@link OfferReadinessView} — are populated in the controller/service tasks (9.x) that own the
 * estimate read model and the readiness calculator. Those are left as empty lists / {@code null}
 * here (never as confidential data) so the confidentiality boundary holds even in the skeleton. The
 * finishing selection surface IS populated: {@link #finishingSelections(OfferEntity)} projects the
 * estimate's FINISHING material lines (Placeholders and chosen lines), mapping only the offer-facing
 * {@code Type_Price_Range} band and chosen-product price (never any cost/margin, Property 21).
 *
 * <p>The assembler is a stateless {@code @Component} mirroring the sibling read-model assembler
 * convention ({@code MaterialsListAssembler}, {@code MarginsListAssembler}).
 */
@Component
public class ClientOfferReadModelAssembler {

    /** Fallback finishing-line label when the line's type carries no localized name. */
    private static final String UNNAMED_FINISHING_LABEL = "—";

    private final OfferVisibilityResolver visibilityResolver;
    private final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private final ImageStorage imageStorage;

    public ClientOfferReadModelAssembler(
            OfferVisibilityResolver visibilityResolver,
            EstimateLineRoomMaterialDao estimateLineRoomMaterialDao,
            ImageStorage imageStorage) {
        this.visibilityResolver = visibilityResolver;
        this.estimateLineRoomMaterialDao = estimateLineRoomMaterialDao;
        this.imageStorage = imageStorage;
    }

    /**
     * Builds the confidential client-facing read model for {@code offer}. Maps only offer-level
     * fields; every price shown is an offer-facing client price. The estimate is referenced by id
     * only (R19.3).
     *
     * <p>The finishing selection surface is projected from the referenced estimate's FINISHING
     * material lines (offer-facing prices only). The remaining price-projection collections and
     * readiness are populated by task 9.x; here they are empty/{@code null} placeholders (never
     * confidential data).
     *
     * @param offer the offer aggregate; must be non-null
     * @return the assembled client read model
     */
    public ClientOfferReadModel toClientReadModel(OfferEntity offer) {
        OfferVisibilityStatus visibility = visibilityResolver.visibilityOf(offer.getStatus());
        return new ClientOfferReadModel(
                offer.getId(),
                offer.getEstimate() != null ? offer.getEstimate().getId() : null,
                offer.getProject() != null ? offer.getProject().getId() : null,
                offer.getStatus(),
                visibility,
                offer.getRevision(),
                selectedPackageCode(offer),
                offer.getTotalNet(),
                offer.getTotalVat(),
                offer.getTotalGross(),
                // Populated in task 9.x from the live-referenced estimate client-facing prices.
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                appliedDiscounts(offer),
                // Finishing selection surface — the estimate's FINISHING material lines, mapping ONLY
                // offer-facing client prices (range band + chosen product price), never any cost/margin.
                finishingSelections(offer),
                negotiationThread(offer),
                // Readiness populated in task 9.x via OfferReadinessCalculator.
                null);
    }

    /**
     * Builds the MANAGER/ADMIN-facing read model: the same offer projection the client sees plus the
     * manager control-state block. The kosztorys cost/margin figures are NOT folded in — per R16
     * they remain their own MANAGER/ADMIN-only read models.
     *
     * <p>Skeleton: the {@link ManagerControlState} is a placeholder wired to the entity's obvious
     * flags; escalation caps and pending-approval resolution are populated in task 9.x.
     *
     * @param offer    the offer aggregate; must be non-null
     * @param editable whether the caller may currently write the offer
     * @return the assembled executor read model
     */
    public ExecutorOfferReadModel toExecutorReadModel(OfferEntity offer, boolean editable) {
        ClientOfferReadModel clientView = toClientReadModel(offer);
        ManagerControlState control = new ManagerControlState(
                editable,
                false,
                null,
                null,
                openRoundCount(offer));
        return new ExecutorOfferReadModel(clientView, control);
    }

    /**
     * Builds the downstream {@link AgreedOfferView} of an approved offer (R7.4). Captures the agreed
     * revision's totals, selected package, and applied discounts.
     *
     * @param offer the approved offer aggregate; must be non-null
     * @return the agreed-version view
     */
    public AgreedOfferView toAgreedView(OfferEntity offer) {
        return new AgreedOfferView(
                offer.getId(),
                offer.getEstimate() != null ? offer.getEstimate().getId() : null,
                offer.getApprovedRevision(),
                selectedPackageCode(offer),
                offer.getTotalNet(),
                offer.getTotalVat(),
                offer.getTotalGross(),
                appliedDiscounts(offer));
    }

    private static String selectedPackageCode(OfferEntity offer) {
        OfferPackageEntity pkg = offer.getSelectedPackage();
        return pkg != null ? pkg.getCode() : null;
    }

    /**
     * Projects the offer's finishing-selection surface (R11): one {@link FinishingSelectionView} per
     * FINISHING {@link EstimateLineRoomMaterialEntity} of the live-referenced estimate — both unchosen
     * Placeholders and chosen lines. Per R11 the client selects per material LINE, so lines are listed
     * one-per-line (keyed by {@code materialLineId}) and NOT pre-aggregated across cells, even when the
     * same finishing TYPE recurs.
     *
     * <p>Walks the SAME {@code estimate → lines → room-qty rows → material rows} graph the matrix
     * assembler traverses, loaded here via {@link EstimateLineRoomMaterialDao#findByEstimateIdAndBranch}
     * (branch {@code finishing}) with the finishing type + chosen product eagerly fetched — so the
     * projection is reachable without a {@code LazyInitializationException} when the assembler runs
     * outside the service transaction (OSIV-independent).
     *
     * <p><b>Confidentiality (Property 21 / R15).</b> Each line maps ONLY offer-facing client prices —
     * the copied {@code Type_Price_Range} band ({@code rangeMin}/{@code rangeMax}) and, when chosen,
     * the collapsed {@code concreteNet}. It reads NO {@code cost_net}, margin, worker rate, or any
     * estimate-internal cost field.
     */
    private List<FinishingSelectionView> finishingSelections(OfferEntity offer) {
        EstimateEntity estimate = offer.getEstimate();
        if (estimate == null || estimate.getId() == null) {
            return new ArrayList<>();
        }
        boolean ru = isRussianLocale();
        OfferPackageEntity selectedPackage = offer.getSelectedPackage();
        List<EstimateLineRoomMaterialEntity> lines =
                estimateLineRoomMaterialDao.findByEstimateIdAndBranch(
                        estimate.getId(), ConsumptionBranch.finishing);

        List<FinishingSelectionView> views = new ArrayList<>(lines.size());
        for (EstimateLineRoomMaterialEntity line : lines) {
            views.add(finishingSelectionView(line, ru, selectedPackage));
        }
        return views;
    }

    /**
     * Maps one FINISHING material line to its client-facing {@link FinishingSelectionView}. A line with
     * no {@code concreteFinishingMaterial} is a Placeholder (shows its {@code Type_Price_Range} band); a
     * chosen line carries the chosen product id/name and its offer price ({@code concreteNet}). Only
     * offer-facing prices are read (Property 21).
     *
     * <p>Also projects the presentational surface the offer finishing tab needs: the
     * {@code appliedFromPackage} flag (the FE filters to package lines), the {@code finishingTypeId}
     * (the FE per-line type+package product picker filters by it), the chosen product's resolved CDN
     * {@code imageUrl}, and — when the chosen product is NOT in the offer's selected package — a
     * {@code differsFromSelected} flag with a representative localized package name. These are all
     * offer-facing/presentational facts (no cost/margin/worker-rate/unit price, Property 21).
     */
    private FinishingSelectionView finishingSelectionView(
            EstimateLineRoomMaterialEntity line, boolean ru, OfferPackageEntity selectedPackage) {
        FinishingMaterialEntity concrete = line.getConcreteFinishingMaterial();
        boolean placeholder = concrete == null;

        Long chosenProductId = null;
        String chosenProductName = null;
        BigDecimal chosenOfferPrice = null;
        String imageUrl = null;
        boolean differsFromSelected = false;
        String chosenProductPackageName = null;
        if (concrete != null) {
            chosenProductId = concrete.getId();
            chosenProductName = finishingMaterialName(concrete, ru);
            // A chosen line's offer price is its collapsed concreteNet; a Placeholder has none yet.
            chosenOfferPrice = line.getConcreteNet();
            imageUrl = imageStorage.toCdnUrl(concrete.getPhoto());
            if (chosenPackageDiffersFromSelected(concrete, selectedPackage)) {
                differsFromSelected = true;
                chosenProductPackageName = representativePackageName(concrete, ru);
            }
        }

        return new FinishingSelectionView(
                line.getId(),
                finishingLineName(line, ru),
                placeholder,
                chosenProductId,
                chosenProductName,
                line.getRangeMin(),
                line.getRangeMax(),
                chosenOfferPrice,
                line.isAppliedFromPackage(),
                line.getFinishingType() != null ? line.getFinishingType().getId() : null,
                imageUrl,
                chosenProductPackageName,
                differsFromSelected);
    }

    /**
     * Whether the chosen product's packages do NOT contain the offer's selected package (compared by
     * package code). Returns {@code false} when there is no selected package (nothing to differ from)
     * so a line is only ever flagged as "differs" against a real selected package.
     */
    private static boolean chosenPackageDiffersFromSelected(
            FinishingMaterialEntity concrete, OfferPackageEntity selectedPackage) {
        if (selectedPackage == null) {
            return false;
        }
        String selectedCode = selectedPackage.getCode();
        return concrete.getPackages().stream()
                .map(OfferPackageEntity::getCode)
                .noneMatch(code -> Objects.equals(code, selectedCode));
    }

    /**
     * A deterministic, representative localized package name of the chosen product: the lowest package
     * code's localized name (so multi-package products resolve to a stable choice across reads), or
     * {@code null} when the product has no packages.
     */
    private static String representativePackageName(FinishingMaterialEntity concrete, boolean ru) {
        return concrete.getPackages().stream()
                .filter(pkg -> pkg.getCode() != null)
                .min(Comparator.comparing(OfferPackageEntity::getCode))
                .map(pkg -> localizedName(ru, pkg.getNameRU(), pkg.getNamePL()))
                .orElse(null);
    }

    /**
     * The localized finishing-line display label from the line's finishing TYPE (mirroring the matrix
     * assembler's localization), falling back to a non-null placeholder label when the type or its name
     * is absent.
     */
    private static String finishingLineName(EstimateLineRoomMaterialEntity line, boolean ru) {
        MaterialTypeEntity type = line.getFinishingType();
        if (type == null) {
            return UNNAMED_FINISHING_LABEL;
        }
        String name = localizedName(ru, type.getNameRU(), type.getNamePL());
        return name != null ? name : UNNAMED_FINISHING_LABEL;
    }

    /**
     * The chosen finishing product's localized display name as {@code "{model|material} · {producer}"}:
     * the concrete product name (its {@code model}, falling back to the generic {@code material} name
     * when the product has no model) joined with its {@code producer} (manufacturer) by a middle dot.
     * Null-safe on every segment (model/material/producer may each be null): returns just the product
     * name when there is no producer, just the producer when there is no product name, and
     * {@code null} when neither is present.
     */
    private static String finishingMaterialName(FinishingMaterialEntity material, boolean ru) {
        if (material == null) {
            return null;
        }
        String productName = material.getModel();
        if (productName == null || productName.isBlank()) {
            productName = material.getMaterial() != null
                    ? localizedName(ru, material.getMaterial().getNameRU(), material.getMaterial().getNamePL())
                    : null;
        }
        String producerName = finishingProducerName(material, ru);

        boolean hasProduct = productName != null && !productName.isBlank();
        boolean hasProducer = producerName != null && !producerName.isBlank();
        if (hasProduct && hasProducer) {
            return productName + " · " + producerName;
        }
        if (hasProduct) {
            return productName;
        }
        if (hasProducer) {
            return producerName;
        }
        return null;
    }

    /** The chosen finishing product's localized manufacturer name, or {@code null} when absent. */
    private static String finishingProducerName(FinishingMaterialEntity material, boolean ru) {
        if (material == null || material.getProducer() == null) {
            return null;
        }
        return localizedName(ru, material.getProducer().getNameRU(), material.getProducer().getNamePL());
    }

    private static String localizedName(boolean ru, String nameRU, String namePL) {
        return ru ? nameRU : namePL;
    }

    /** Whether the request locale is Russian (RU names), mirroring the sibling read-path convention. */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /** Maps the offer's applied discounts to their client-facing view (offer-level facts only). */
    private static List<AppliedDiscountView> appliedDiscounts(OfferEntity offer) {
        List<AppliedDiscountView> views = new ArrayList<>();
        if (offer.getDiscounts() != null) {
            for (OfferDiscountEntity discount : offer.getDiscounts()) {
                views.add(new AppliedDiscountView(
                        discount.getScope(),
                        discount.getKind(),
                        discount.getValue(),
                        discount.getTargetId(),
                        // Effective amount is resolved by DiscountResolver/OfferTotalsCalculator in task 9.x.
                        null));
            }
        }
        return views;
    }

    /** Maps the ordered negotiation thread to its client-facing view (offer-level facts only). */
    private static List<NegotiationRoundView> negotiationThread(OfferEntity offer) {
        List<NegotiationRoundView> views = new ArrayList<>();
        if (offer.getNegotiationRounds() != null) {
            for (OfferNegotiationRoundEntity round : offer.getNegotiationRounds()) {
                views.add(new NegotiationRoundView(
                        round.getId(),
                        round.getRoundNo(),
                        round.getOfferRevision(),
                        round.getInitiatorRole(),
                        round.getKind(),
                        round.getStatus(),
                        round.isAdminApproved(),
                        round.getScope(),
                        round.getTargetId(),
                        round.getValueKind(),
                        round.getValue(),
                        round.getJustification(),
                        round.getExplanation(),
                        round.getClientComment(),
                        round.getCreatedDate()));
            }
        }
        return views;
    }

    private static int openRoundCount(OfferEntity offer) {
        if (offer.getNegotiationRounds() == null) {
            return 0;
        }
        return (int) offer.getNegotiationRounds().stream()
                .filter(r -> r.getStatus() == com.foremen.dao.model.NegotiationRoundStatus.OPEN)
                .count();
    }
}
