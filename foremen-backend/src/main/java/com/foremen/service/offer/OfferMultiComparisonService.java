package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.offer.OfferSnapshotViews.MultiComparison;
import com.foremen.service.offer.OfferSnapshotViews.MultiGroup;
import com.foremen.service.offer.OfferSnapshotViews.MultiOffer;
import com.foremen.service.offer.OfferSnapshotViews.MultiWork;
import com.foremen.service.offer.OfferSnapshotViews.OfferValue;
import com.foremen.service.offer.OfferSnapshotViews.RoomQuantity;
import com.foremen.service.offer.OfferSnapshotViews.WorkOffer;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07b Round 2 (D4): comparison of 2 to 10 offers of one project, read from their snapshots.
 * Differences are computed by the client against the first requested offer. Never writes.
 *
 * <p>Redaction by allow-list: only client-visible fields are copied (no base prices, no restore data).
 * A DRAFT offer is a 404 for a CLIENT (indistinguishable from a missing one).
 */
@Service
@RequiredArgsConstructor
public class OfferMultiComparisonService {

    static final int MIN_OFFERS = 2;
    static final int MAX_OFFERS = 10;

    private static final String NOT_FOUND = "error.entity.not.found";

    private final OfferDao offerDao;
    private final OfferVisibilityResolver visibilityResolver;
    private final WorkCategoryDao workCategoryDao;

    @Transactional(readOnly = true)
    public MultiComparison compareMany(List<Long> ids, boolean client, boolean ru) {
        if (ids == null || ids.size() < MIN_OFFERS || ids.size() > MAX_OFFERS
                || new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(Objects::isNull)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.offer.compare.many.count",
                    MIN_OFFERS, MAX_OFFERS);
        }
        List<OfferEntity> offers = new ArrayList<>();
        for (Long id : ids) {
            OfferEntity offer = offerDao.findById(id).orElseThrow(
                    () -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", id));
            if (client && visibilityResolver.visibilityOf(offer.getStatus()) == OfferVisibilityStatus.DRAFT) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
            }
            offers.add(offer);
        }
        Long projectId = offers.get(0).getProject().getId();
        for (OfferEntity offer : offers) {
            if (!Objects.equals(projectId, offer.getProject().getId())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.offer.compare.different.project");
            }
            if (offer.getSnapshot() == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.offer.compare.many.snapshot.missing",
                        offer.getId());
            }
        }

        List<MultiOffer> heads = new ArrayList<>();
        Map<Long, String> categoryNames = new LinkedHashMap<>();
        // categoryId -> offerId -> value
        Map<Long, Map<Long, BigDecimal>> groupValues = new LinkedHashMap<>();
        Map<Long, WorkAcc> works = new LinkedHashMap<>();
        for (OfferEntity offer : offers) {
            OfferSnapshot s = offer.getSnapshot();
            heads.add(new MultiOffer(offer.getId(), displayName(offer), offer.getStatus(), offer.isInactive(),
                    offer.getRevision(), s.totalNet(), s.totalVat(), s.totalGross(),
                    s.appliedPackageCode(), OfferSnapshotViews.packageName(s, ru)));
            Map<Long, String> roomLabels = new HashMap<>();
            for (OfferSnapshot.Room r : nz(s.rooms())) {
                roomLabels.put(r.id(), r.label());
            }
            for (OfferSnapshot.Group g : nz(s.groups())) {
                categoryNames.putIfAbsent(g.categoryId(), OfferSnapshotViews.pick(ru, g.nameRu(), g.namePl()));
                BigDecimal sum = BigDecimal.ZERO;
                for (OfferSnapshot.Row r : nz(g.rows())) {
                    sum = sum.add(r.valueNet() != null ? r.valueNet() : BigDecimal.ZERO);
                    WorkAcc acc = works.computeIfAbsent(r.workItemId(), k -> new WorkAcc(r, g.categoryId(),
                            OfferSnapshotViews.pick(ru, r.nameRu(), r.namePl()),
                            OfferSnapshotViews.pick(ru, r.unitNameRu(), r.unitNamePl())));
                    acc.perOffer.put(offer.getId(), new WorkOffer(offer.getId(), r.quantity(), r.valueNet(),
                            rooms(r, roomLabels)));
                }
                groupValues.computeIfAbsent(g.categoryId(), k -> new LinkedHashMap<>()).merge(
                        offer.getId(), sum, BigDecimal::add);
            }
        }

        Map<Long, String> codes = new HashMap<>();
        for (WorkCategoryEntity c : workCategoryDao.findAllById(categoryNames.keySet())) {
            codes.put(c.getId(), c.getCode());
        }

        List<MultiGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, String> e : categoryNames.entrySet()) {
            Map<Long, BigDecimal> perOffer = groupValues.getOrDefault(e.getKey(), Map.of());
            List<OfferValue> values = new ArrayList<>();
            for (OfferEntity offer : offers) {
                values.add(new OfferValue(offer.getId(), perOffer.get(offer.getId())));
            }
            groups.add(new MultiGroup(e.getKey(), codes.get(e.getKey()), e.getValue(), values));
        }
        List<MultiWork> workList = new ArrayList<>();
        for (Map.Entry<Long, WorkAcc> e : works.entrySet()) {
            WorkAcc a = e.getValue();
            List<WorkOffer> perOffer = new ArrayList<>();
            for (OfferEntity offer : offers) {
                perOffer.add(a.perOffer.getOrDefault(offer.getId(),
                        new WorkOffer(offer.getId(), null, null, List.of())));
            }
            workList.add(new MultiWork(e.getKey(), a.name, a.row.unitCode(), a.unitName,
                    a.categoryId, codes.get(a.categoryId), perOffer));
        }
        return new MultiComparison(heads, groups, workList);
    }

    private static List<RoomQuantity> rooms(OfferSnapshot.Row row, Map<Long, String> labels) {
        Map<Long, BigDecimal> byRoom = new LinkedHashMap<>();
        for (OfferSnapshot.Cell c : nz(row.cells())) {
            byRoom.merge(c.roomId(), c.quantity() != null ? c.quantity() : BigDecimal.ZERO, BigDecimal::add);
        }
        List<RoomQuantity> result = new ArrayList<>();
        byRoom.forEach((roomId, qty) -> result.add(new RoomQuantity(roomId, labels.get(roomId), qty)));
        return result;
    }

    private static String displayName(OfferEntity offer) {
        return offer.getName() != null && !offer.getName().isBlank() ? offer.getName() : "Offer #" + offer.getId();
    }

    private static <T> List<T> nz(List<T> list) {
        return list != null ? list : List.of();
    }

    private static final class WorkAcc {
        final OfferSnapshot.Row row;
        final Long categoryId;
        final String name;
        final String unitName;
        final Map<Long, WorkOffer> perOffer = new HashMap<>();

        WorkAcc(OfferSnapshot.Row row, Long categoryId, String name, String unitName) {
            this.row = row;
            this.categoryId = categoryId;
            this.name = name;
            this.unitName = unitName;
        }
    }
}
