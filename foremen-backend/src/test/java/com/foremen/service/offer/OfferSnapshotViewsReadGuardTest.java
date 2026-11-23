package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.OfferStatus;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

/**
 * FOR-05-07b (1.2, design "read-time guard"): a stored version 1 snapshot can still hold rows without
 * room cells; the view built from it drops those rows and the groups left without rows.
 */
class OfferSnapshotViewsReadGuardTest {

    private static OfferSnapshot.Row row(long workItemId, List<OfferSnapshot.Cell> cells) {
        return new OfferSnapshot.Row(workItemId, "ru" + workItemId, "pl" + workItemId, "M2", "m2", "m2",
                BigDecimal.ONE, BigDecimal.TEN, null, BigDecimal.TEN, null, cells, null, null, null, null);
    }

    @Test
    void rowsWithoutRoomQuantityAndEmptyGroupsAreDropped() {
        OfferSnapshot.Row withCell = row(1L, List.of(new OfferSnapshot.Cell(5L, new BigDecimal("2"))));
        OfferSnapshot.Row noCells = row(2L, List.of());
        OfferSnapshot.Row nullCells = row(3L, null);
        OfferSnapshot.Row zeroCell = row(4L, List.of(new OfferSnapshot.Cell(5L, BigDecimal.ZERO)));
        OfferSnapshot snapshot = new OfferSnapshot(1, "2026-01-01T00:00:00Z", new BigDecimal("8"),
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, List.of(new OfferSnapshot.Room(5L, "Room A")),
                List.of(new OfferSnapshot.Group(10L, "g1", "g1", List.of(withCell, noCells)),
                        new OfferSnapshot.Group(20L, "g2", "g2", List.of(nullCells, zeroCell))));

        SnapshotView view = OfferSnapshotViews.toView(7L, "Offer", OfferStatus.SENT, snapshot, false, true, false);

        assertThat(view.groups()).hasSize(1);
        assertThat(view.groups().get(0).categoryId()).isEqualTo(10L);
        assertThat(view.groups().get(0).rows()).extracting(OfferSnapshotViews.RowView::workItemId).containsExactly(1L);
    }
}
