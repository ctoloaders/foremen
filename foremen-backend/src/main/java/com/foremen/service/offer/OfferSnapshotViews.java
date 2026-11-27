package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.OfferStatus;

/**
 * FOR-05-07 Batch 2: response shapes of the snapshot read, the offers list and the comparison.
 *
 * <p>CLIENT redaction is an allow-list: {@link #toView} copies only client-visible fields and sets the
 * staff-only {@code baseUnitPrice}/{@code baseValueNet} solely when {@code includeBase} is true (roles
 * allowed by {@code ClientOfferReadModelAssembler#canSeeLabourComparison}). The comparison never
 * carries base prices for any role. The snapshot's restore data (unitPriceOverride, workPriceId, lineNo,
 * comment) is never copied into any view; only the package marker is added.
 */
public final class OfferSnapshotViews {

    private OfferSnapshotViews() {
    }

    /** One entry of the project's offers list. */
    public record OfferSummary(
            Long id, String name, Integer revision, OfferStatus status,
            BigDecimal totalNet, BigDecimal totalGross, boolean hasSnapshot, String createdDate,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName,
            boolean inactive) {
    }

    public record SnapshotView(
            Long offerId, String name, OfferStatus status, String capturedAt, BigDecimal vatRatePct,
            BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
            List<OfferSnapshot.Room> rooms, List<GroupView> groups,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName,
            boolean inactive) {
    }

    public record GroupView(Long categoryId, String name, List<RowView> rows) {
    }

    public record RowView(
            Long workItemId, String name, String unitCode, String unitName,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal valueNet,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal baseUnitPrice,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal baseValueNet,
            List<CellView> cells) {
    }

    /**
     * FOR-05-04 (Requirement 21.8): the client-safe cell of the snapshot read model. It copies only the
     * room and the quantity; the staff-only AI explanation of {@link OfferSnapshot.Cell#ai()} is served
     * through {@link StaffSnapshotView} and is never reachable from {@link SnapshotView}.
     */
    public record CellView(Long roomId, BigDecimal quantity) {
    }

    /**
     * FOR-05-04 (Requirement 21.9): the staff read of an offer snapshot. The JSON is the
     * {@link SnapshotView} object plus {@code aiCells}: the AI explanation of every cell that has one,
     * keyed by {@code (workItemId, roomId)}. Never returned to CLIENT.
     */
    public record StaffSnapshotView(
            @com.fasterxml.jackson.annotation.JsonUnwrapped SnapshotView snapshot,
            List<CellAiView> aiCells) {
    }

    /** One staff-only cell explanation of {@link StaffSnapshotView}. */
    public record CellAiView(Long workItemId, Long roomId, Boolean volumeOverridden, String formulaKey,
                             Boolean fallbackUsed, com.foremen.service.estimate.CellAiMeta ai) {
    }

    /** The AI explanations of the cells of {@code s} (rows of a version 2 snapshot contribute nothing). */
    public static List<CellAiView> aiCells(OfferSnapshot s) {
        List<CellAiView> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (OfferSnapshot.Group g : nz(s.groups())) {
            for (OfferSnapshot.Row r : nz(g.rows())) {
                for (OfferSnapshot.Cell c : nz(r.cells())) {
                    if (c != null && c.ai() != null) {
                        out.add(new CellAiView(r.workItemId(), c.roomId(), c.volumeOverridden(),
                                c.formulaKey(), c.fallbackUsed(), c.ai()));
                    }
                }
            }
        }
        return out;
    }

    public record CompareSide(
            Long offerId, String name, BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName,
            boolean inactive) {
    }

    public record RoomDiff(Long roomId, String label, BigDecimal leftQuantity, BigDecimal rightQuantity) {
    }

    public record WorkDiff(
            Long workItemId, String name, String categoryName, String unitCode, String unitName,
            BigDecimal leftQuantity, BigDecimal rightQuantity,
            BigDecimal leftUnitPrice, BigDecimal rightUnitPrice,
            BigDecimal leftValueNet, BigDecimal rightValueNet, BigDecimal deltaValueNet,
            List<RoomDiff> rooms) {
    }

    /** {@code left} is the base, {@code right} the compared offer: "added" means present only on the right. */
    public record Comparison(
            CompareSide left, CompareSide right,
            List<WorkDiff> added, List<WorkDiff> removed, List<WorkDiff> changed,
            BigDecimal deltaTotalNet, BigDecimal deltaTotalGross) {
    }

    /** One compared offer of {@link MultiComparison}; {@code version} is the offer revision. */
    public record MultiOffer(
            Long id, String name, OfferStatus status, boolean inactive, Integer version,
            BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName) {
    }

    /** Net value of a category in one offer; {@code valueNet} is null when the offer has no such category. */
    public record OfferValue(Long offerId, BigDecimal valueNet) {
    }

    public record MultiGroup(Long categoryId, String categoryCode, String name, List<OfferValue> values) {
    }

    public record RoomQuantity(Long roomId, String label, BigDecimal quantity) {
    }

    /** A work in one offer; quantity and valueNet are null (rooms empty) when the offer lacks the work. */
    public record WorkOffer(Long offerId, BigDecimal quantity, BigDecimal valueNet, List<RoomQuantity> rooms) {
    }

    public record MultiWork(
            Long workItemId, String name, String unitCode, String unitName,
            Long categoryId, String categoryCode, List<WorkOffer> offers) {
    }

    /** Comparison of 2 to 10 offers of one project; {@code offers} keeps the order of the requested ids. */
    public record MultiComparison(List<MultiOffer> offers, List<MultiGroup> groups, List<MultiWork> works) {
    }

    public static SnapshotView toView(Long offerId, String name, OfferStatus status, OfferSnapshot s,
                                      boolean ru, boolean includeBase, boolean inactive) {
        List<GroupView> groups = new ArrayList<>();
        for (OfferSnapshot.Group g : nz(s.groups())) {
            List<RowView> rows = new ArrayList<>();
            for (OfferSnapshot.Row r : nz(g.rows())) {
                if (!hasRoomQuantity(r)) {
                    // Read-time guard (design 1.2): version 1 snapshots may still hold rows without cells.
                    continue;
                }
                rows.add(new RowView(
                        r.workItemId(), pick(ru, r.nameRu(), r.namePl()), r.unitCode(),
                        pick(ru, r.unitNameRu(), r.unitNamePl()),
                        r.quantity(), r.unitPrice(), r.valueNet(),
                        includeBase ? r.baseUnitPrice() : null,
                        includeBase ? r.baseValueNet() : null,
                        cellViews(r.cells())));
            }
            if (!rows.isEmpty()) {
                groups.add(new GroupView(g.categoryId(), pick(ru, g.nameRu(), g.namePl()), rows));
            }
        }
        return new SnapshotView(offerId, name, status, s.capturedAt(), s.vatRatePct(),
                s.totalNet(), s.totalVat(), s.totalGross(), nz(s.rooms()), groups,
                s.appliedPackageCode(), packageName(s, ru), inactive);
    }

    /** Allow-list copy of the snapshot cells: room and quantity only. */
    private static List<CellView> cellViews(List<OfferSnapshot.Cell> cells) {
        List<CellView> out = new ArrayList<>();
        for (OfferSnapshot.Cell c : nz(cells)) {
            if (c != null) {
                out.add(new CellView(c.roomId(), c.quantity()));
            }
        }
        return out;
    }

    /** True when the row has at least one room cell with a positive quantity. */
    static boolean hasRoomQuantity(OfferSnapshot.Row r) {
        for (OfferSnapshot.Cell c : nz(r.cells())) {
            if (c != null && c.quantity() != null && c.quantity().signum() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Localized applied-package name of a snapshot; null when no package was applied. */
    public static String packageName(OfferSnapshot s, boolean ru) {
        if (s == null || s.appliedPackageCode() == null) {
            return null;
        }
        String name = pick(ru, s.appliedPackageNameRu(), s.appliedPackageNamePl());
        return name != null && !name.isBlank() ? name : s.appliedPackageCode();
    }

    public static String pick(boolean ru, String nameRu, String namePl) {
        String primary = ru ? nameRu : namePl;
        return primary != null && !primary.isBlank() ? primary : (ru ? namePl : nameRu);
    }

    private static <T> List<T> nz(List<T> list) {
        return list != null ? list : List.of();
    }
}
