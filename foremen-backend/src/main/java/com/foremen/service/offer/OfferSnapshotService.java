package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.offer.OfferSnapshotViews.Comparison;
import com.foremen.service.offer.OfferSnapshotViews.CompareSide;
import com.foremen.service.offer.OfferSnapshotViews.OfferSummary;
import com.foremen.service.offer.OfferSnapshotViews.RoomDiff;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;
import com.foremen.service.offer.OfferSnapshotViews.WorkDiff;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07 Batch 2 (task 10): read side of offer snapshots — the project's offers list, the read-only
 * snapshot of one offer, and the comparison of two offers of the same project. Never writes.
 *
 * <p>Audience rules: a CLIENT only sees offers whose derived visibility is not {@code DRAFT}
 * (a hidden offer is indistinguishable from a missing one, 404) and never receives base prices.
 * The comparison carries client-visible data only for every role (no base prices, no labour
 * comparison, no margins).
 */
@Service
@RequiredArgsConstructor
public class OfferSnapshotService {

    private static final String NOT_FOUND = "error.entity.not.found";

    private final OfferDao offerDao;
    private final OfferVisibilityResolver visibilityResolver;

    /** Lists with the package name localized from the request locale (the controller passes no locale). */
    @Transactional(readOnly = true)
    public List<OfferSummary> listByProject(Long projectId, boolean client) {
        Locale locale = LocaleContextHolder.getLocale();
        return listByProject(projectId, client, locale != null && "ru".equalsIgnoreCase(locale.getLanguage()));
    }

    @Transactional(readOnly = true)
    public List<OfferSummary> listByProject(Long projectId, boolean client, boolean ru) {
        List<OfferSummary> result = new ArrayList<>();
        List<OfferEntity> offers = offerDao.findByProjectIdOrderByIdAsc(projectId);
        for (int i = offers.size() - 1; i >= 0; i--) { // newest first
            OfferEntity o = offers.get(i);
            if (client && !isClientVisible(o)) {
                continue;
            }
            result.add(new OfferSummary(
                    o.getId(), displayName(o, i + 1), o.getRevision(), o.getStatus(),
                    o.getTotalNet(), o.getTotalGross(), o.getSnapshot() != null,
                    o.getCreatedDate() != null ? o.getCreatedDate().toString() : null,
                    o.getSnapshot() != null ? o.getSnapshot().appliedPackageCode() : null,
                    OfferSnapshotViews.packageName(o.getSnapshot(), ru), o.isInactive()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public SnapshotView snapshot(Long offerId, boolean client, boolean includeBase, boolean ru) {
        OfferEntity offer = load(offerId, client);
        OfferSnapshot snapshot = requireSnapshot(offer);
        return OfferSnapshotViews.toView(
                offer.getId(), displayName(offer, null), offer.getStatus(), snapshot, ru, includeBase && !client, offer.isInactive());
    }

    /** FOR-05-04 (R21.9): the staff read, the snapshot view plus the per-cell AI explanations. Never for CLIENT. */
    @Transactional(readOnly = true)
    public OfferSnapshotViews.StaffSnapshotView staffSnapshot(Long offerId, boolean includeBase, boolean ru) {
        OfferEntity offer = load(offerId, false);
        OfferSnapshot snapshot = requireSnapshot(offer);
        SnapshotView view = OfferSnapshotViews.toView(
                offer.getId(), displayName(offer, null), offer.getStatus(), snapshot, ru, includeBase, offer.isInactive());
        return new OfferSnapshotViews.StaffSnapshotView(view, OfferSnapshotViews.aiCells(snapshot));
    }

    @Transactional(readOnly = true)
    public Comparison compare(Long leftId, Long rightId, boolean client, boolean ru) {
        OfferEntity left = load(leftId, client);
        OfferEntity right = load(rightId, client);
        if (!Objects.equals(left.getProject().getId(), right.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.offer.compare.different.project");
        }
        OfferSnapshot ls = requireSnapshot(left);
        OfferSnapshot rs = requireSnapshot(right);

        Map<Long, RowCtx> leftRows = index(ls, ru);
        Map<Long, RowCtx> rightRows = index(rs, ru);
        Map<Long, String> roomLabels = new LinkedHashMap<>();
        for (OfferSnapshot.Room r : nz(ls.rooms())) {
            roomLabels.put(r.id(), r.label());
        }
        for (OfferSnapshot.Room r : nz(rs.rooms())) {
            roomLabels.put(r.id(), r.label());
        }

        List<WorkDiff> added = new ArrayList<>();
        List<WorkDiff> removed = new ArrayList<>();
        List<WorkDiff> changed = new ArrayList<>();
        for (Map.Entry<Long, RowCtx> e : rightRows.entrySet()) {
            RowCtx l = leftRows.get(e.getKey());
            RowCtx r = e.getValue();
            if (l == null) {
                added.add(diff(null, r, roomLabels));
            } else if (differs(l.row(), r.row())) {
                changed.add(diff(l, r, roomLabels));
            }
        }
        for (Map.Entry<Long, RowCtx> e : leftRows.entrySet()) {
            if (!rightRows.containsKey(e.getKey())) {
                removed.add(diff(e.getValue(), null, roomLabels));
            }
        }
        return new Comparison(
                side(left, ls, ru), side(right, rs, ru), added, removed, changed,
                sub(rs.totalNet(), ls.totalNet()), sub(rs.totalGross(), ls.totalGross()));
    }

    // --- helpers ---

    private OfferEntity load(Long offerId, boolean client) {
        OfferEntity offer = offerDao.findById(offerId).orElseThrow(
                () -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", offerId));
        if (client) {
            visibilityResolver.assertClientVisible(offer.getStatus(), offerId);
        }
        return offer;
    }

    private boolean isClientVisible(OfferEntity o) {
        return visibilityResolver.visibilityOf(o.getStatus()) != OfferVisibilityStatus.DRAFT;
    }

    private static OfferSnapshot requireSnapshot(OfferEntity offer) {
        if (offer.getSnapshot() == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.offer.snapshot.missing");
        }
        return offer.getSnapshot();
    }

    private String displayName(OfferEntity offer, Integer position) {
        if (offer.getName() != null && !offer.getName().isBlank()) {
            return offer.getName();
        }
        return position != null ? "Offer " + position : "Offer #" + offer.getId();
    }

    private CompareSide side(OfferEntity o, OfferSnapshot s, boolean ru) {
        return new CompareSide(o.getId(), displayName(o, null), s.totalNet(), s.totalVat(), s.totalGross(),
                s.appliedPackageCode(), OfferSnapshotViews.packageName(s, ru), o.isInactive());
    }

    private record RowCtx(OfferSnapshot.Row row, String categoryName, String name, String unitName) {
    }

    private static Map<Long, RowCtx> index(OfferSnapshot s, boolean ru) {
        Map<Long, RowCtx> map = new LinkedHashMap<>();
        for (OfferSnapshot.Group g : nz(s.groups())) {
            String category = OfferSnapshotViews.pick(ru, g.nameRu(), g.namePl());
            for (OfferSnapshot.Row r : nz(g.rows())) {
                map.put(r.workItemId(), new RowCtx(r, category,
                        OfferSnapshotViews.pick(ru, r.nameRu(), r.namePl()),
                        OfferSnapshotViews.pick(ru, r.unitNameRu(), r.unitNamePl())));
            }
        }
        return map;
    }

    private static boolean differs(OfferSnapshot.Row a, OfferSnapshot.Row b) {
        return cmp(a.quantity(), b.quantity()) != 0
                || cmp(a.unitPrice(), b.unitPrice()) != 0
                || cmp(a.valueNet(), b.valueNet()) != 0
                || !roomQty(a).equals(roomQty(b));
    }

    private static Map<Long, BigDecimal> roomQty(OfferSnapshot.Row r) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        for (OfferSnapshot.Cell c : nz(r.cells())) {
            m.merge(c.roomId(), c.quantity() != null ? c.quantity().stripTrailingZeros() : BigDecimal.ZERO,
                    BigDecimal::add);
        }
        m.replaceAll((k, v) -> v.stripTrailingZeros());
        return m;
    }

    private static WorkDiff diff(RowCtx l, RowCtx r, Map<Long, String> roomLabels) {
        RowCtx any = r != null ? r : l;
        Map<Long, BigDecimal> lq = l != null ? roomQty(l.row()) : Map.of();
        Map<Long, BigDecimal> rq = r != null ? roomQty(r.row()) : Map.of();
        Map<Long, BigDecimal> all = new LinkedHashMap<>();
        lq.keySet().forEach(k -> all.put(k, null));
        rq.keySet().forEach(k -> all.put(k, null));
        List<RoomDiff> rooms = new ArrayList<>();
        for (Long roomId : all.keySet()) {
            BigDecimal a = lq.get(roomId);
            BigDecimal b = rq.get(roomId);
            if (a == null || b == null || a.compareTo(b) != 0) {
                rooms.add(new RoomDiff(roomId, roomLabels.get(roomId), a, b));
            }
        }
        BigDecimal leftNet = l != null ? l.row().valueNet() : null;
        BigDecimal rightNet = r != null ? r.row().valueNet() : null;
        return new WorkDiff(
                any.row().workItemId(), any.name(), any.categoryName(), any.row().unitCode(), any.unitName(),
                l != null ? l.row().quantity() : null, r != null ? r.row().quantity() : null,
                l != null ? l.row().unitPrice() : null, r != null ? r.row().unitPrice() : null,
                leftNet, rightNet, sub(rightNet != null ? rightNet : BigDecimal.ZERO,
                        leftNet != null ? leftNet : BigDecimal.ZERO),
                rooms);
    }

    private static int cmp(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).compareTo(b != null ? b : BigDecimal.ZERO);
    }

    private static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).subtract(b != null ? b : BigDecimal.ZERO);
    }

    private static <T> List<T> nz(List<T> list) {
        return list != null ? list : List.of();
    }
}
