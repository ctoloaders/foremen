package com.foremen.controller.model.staff;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.margins.Margin;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.procurement.approval.ApprovalActionRules;
import com.foremen.service.procurement.approval.ApprovalDeadlinePolicy;
import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.StaffAction;
import com.foremen.service.procurement.approval.StaffApprovalData;
import com.foremen.service.procurement.approval.StaffOptionSource;
import com.foremen.service.procurement.approval.StaffPositionSource;
import com.foremen.service.procurement.approval.StaffRoundSource;
import com.foremen.service.procurement.planner.PositionMarginCalculator;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * FOR-11-02 (design 4, R4.5, R4.7): the only builder of the staff DTOs. Rejects every audience other than
 * {@code STAFF_FULL} with 403 {@code error.positionApproval.staff.forbidden}. Money passes through
 * {@link PositionPriceVisibility} (for {@code STAFF_FULL} it is the raw value). BUYER, MANAGER and ADMIN
 * share the {@code STAFF_FULL} audience and so see the same money; they differ only in {@code allowedActions}
 * ({@link ApprovalActionRules#staffActions}: write actions for MANAGER/ADMIN only).
 */
@Component
public class StaffViewAssembler {

    public static final String ERROR_FORBIDDEN = "error.positionApproval.staff.forbidden";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ImageStorage imageStorage;

    public StaffViewAssembler(ImageStorage imageStorage) {
        this.imageStorage = imageStorage;
    }

    /**
     * @param audience caller audience; anything but {@code STAFF_FULL} is rejected
     * @param roleCode caller role code ({@code ADMIN}, {@code MANAGER}, {@code BUYER})
     * @param pl       {@code true} to resolve names in Polish, otherwise Russian
     */
    public List<StaffApprovalPositionDto> assemble(StaffApprovalData data, Audience audience, String roleCode,
                                                   LocalDate today, boolean pl) {
        if (audience != Audience.STAFF_FULL) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ERROR_FORBIDDEN);
        }
        return data.positions().stream()
                .map(p -> position(p, data, audience, roleCode, today, pl))
                .toList();
    }

    private StaffApprovalPositionDto position(StaffPositionSource p, StaffApprovalData data, Audience audience,
                                              String roleCode, LocalDate today, boolean pl) {
        List<StaffRoundSource> rounds = data.roundsByPosition().getOrDefault(p.positionId(), List.of());
        StaffRoundSource open = rounds.stream().filter(StaffViewAssembler::isOpenDecision).findFirst().orElse(null);
        List<StaffRoundSource> suggestions = rounds.stream().filter(StaffViewAssembler::isOpenSuggestion).toList();

        PositionMoney money = PositionPriceVisibility.apply(audience, p.sourcingPath(), p.status(),
                new PositionMoney(p.plannedPurchasePrice(), p.actualPurchasePrice(), null, p.salePrice(), null,
                        p.deliveryCostShare()));
        BigDecimal purchaseUnit = money.actualPurchasePrice() != null
                ? money.actualPurchasePrice() : money.plannedPurchasePrice();
        Margin unit = PositionMarginCalculator.margin(money.salePrice(), money.actualPurchasePrice(),
                money.plannedPurchasePrice(), BigDecimal.ONE, null);
        Margin line = PositionMarginCalculator.margin(money.salePrice(), money.actualPurchasePrice(),
                money.plannedPurchasePrice(), p.quantity(), money.deliveryCostShare());

        Set<StaffAction> actions = ApprovalActionRules.staffActions(audience, roleCode, p.sourcingPath(),
                p.status(), open == null ? null : open.kind(), suggestions.size());
        LocalDate deadline = open != null ? open.decisionDeadline() : p.decisionDeadline();
        boolean overdue = open != null
                && ApprovalDeadlinePolicy.isOverdue(open.status(), open.decisionDeadline(), today);

        List<StaffRoundDto> history = rounds.stream()
                .filter(r -> r != open && !suggestions.contains(r))
                .sorted(Comparator.comparing(StaffRoundSource::roundNo).reversed())
                .map(r -> round(r, p, data, pl))
                .toList();

        return new StaffApprovalPositionDto(p.positionId(), p.blockNo(), p.materialId(), p.model(), p.sku(),
                name(p.producerNameRu(), p.producerNamePl(), pl), p.quantity(),
                name(p.unitNameRu(), p.unitNamePl(), pl), p.status().name(), p.sourcingPath(),
                money.salePrice(), money.plannedPurchasePrice(), purchaseUnit, unit.amount(), percent(unit),
                line.amount(), name(p.sellerNameRu(), p.sellerNamePl(), pl), p.requestId(), p.requestReference(),
                p.needByDate(), p.orderByDate(), p.plannedDeliveryDate(), deadline, overdue,
                open == null ? null : round(open, p, data, pl),
                suggestions.stream().map(r -> round(r, p, data, pl)).toList(), history, actions,
                imageStorage.toCdnUrl(p.photo()), name(p.typeNameRu(), p.typeNamePl(), pl),
                p.fromEstimate(), p.estimateChanged(), p.estimateRemoved());
    }

    private StaffRoundDto round(StaffRoundSource r, StaffPositionSource p, StaffApprovalData data, boolean pl) {
        List<StaffOptionDto> options = data.optionsByRound().getOrDefault(r.roundId(), List.of()).stream()
                .map(o -> option(o, p, pl)).toList();
        return new StaffRoundDto(r.roundId(), r.roundNo(), r.kind(), r.status(), r.decisionDeadline(),
                r.managerNote(), r.suggestionLink(), r.decisionComment(), r.decidedAt(), r.decidedById(),
                r.decidedSide(), r.onBehalf(), r.sourceSuggestionId(), options, r.chosenOptionId());
    }

    private StaffOptionDto option(StaffOptionSource o, StaffPositionSource p, boolean pl) {
        Margin unit = PositionMarginCalculator.margin(o.unitSalePrice(), o.internalUnitPurchasePrice(), null,
                BigDecimal.ONE, null);
        Margin line = PositionMarginCalculator.margin(o.unitSalePrice(), o.internalUnitPurchasePrice(), null,
                p.quantity(), null);
        return new StaffOptionDto(o.optionId(), o.materialId(), o.model(), o.sku(),
                name(o.producerNameRu(), o.producerNamePl(), pl), o.link(), o.unitSalePrice(),
                o.internalUnitPurchasePrice(), unit.amount(), percent(unit), line.amount(),
                name(o.sellerNameRu(), o.sellerNamePl(), pl));
    }

    private static boolean isOpenDecision(StaffRoundSource r) {
        return r.status() == ApprovalRoundStatus.OPEN && r.kind() != ApprovalRoundKind.CLIENT_SUGGESTION;
    }

    private static boolean isOpenSuggestion(StaffRoundSource r) {
        return r.status() == ApprovalRoundStatus.OPEN && r.kind() == ApprovalRoundKind.CLIENT_SUGGESTION;
    }

    /** Margin fraction as a percentage with scale 2; {@code null} when unavailable. */
    private static BigDecimal percent(Margin m) {
        return m.pct() == null ? null : m.pct().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    private static String name(String ru, String plName, boolean pl) {
        String primary = pl ? plName : ru;
        return primary != null ? primary : (pl ? ru : plName);
    }
}
