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
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName) {
    }

    public record SnapshotView(
            Long offerId, String name, OfferStatus status, String capturedAt, BigDecimal vatRatePct,
            BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
            List<OfferSnapshot.Room> rooms, List<GroupView> groups,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName) {
    }

    public record GroupView(Long categoryId, String name, List<RowView> rows) {
    }

    public record RowView(
            Long workItemId, String name, String unitCode, String unitName,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal valueNet,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal baseUnitPrice,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal baseValueNet,
            List<OfferSnapshot.Cell> cells) {
    }

    public record CompareSide(
            Long offerId, String name, BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String appliedPackageName) {
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

    public static SnapshotView toView(Long offerId, String name, OfferStatus status, OfferSnapshot s,
                                      boolean ru, boolean includeBase) {
        List<GroupView> groups = new ArrayList<>();
        for (OfferSnapshot.Group g : nz(s.groups())) {
            List<RowView> rows = new ArrayList<>();
            for (OfferSnapshot.Row r : nz(g.rows())) {
                rows.add(new RowView(
                        r.workItemId(), pick(ru, r.nameRu(), r.namePl()), r.unitCode(),
                        pick(ru, r.unitNameRu(), r.unitNamePl()),
                        r.quantity(), r.unitPrice(), r.valueNet(),
                        includeBase ? r.baseUnitPrice() : null,
                        includeBase ? r.baseValueNet() : null,
                        nz(r.cells())));
            }
            groups.add(new GroupView(g.categoryId(), pick(ru, g.nameRu(), g.namePl()), rows));
        }
        return new SnapshotView(offerId, name, status, s.capturedAt(), s.vatRatePct(),
                s.totalNet(), s.totalVat(), s.totalGross(), nz(s.rooms()), groups,
                s.appliedPackageCode(), packageName(s, ru));
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
