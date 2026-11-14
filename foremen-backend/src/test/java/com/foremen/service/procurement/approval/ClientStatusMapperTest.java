package com.foremen.service.procurement.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** <b>Validates: Requirements 4.3</b> */
class ClientStatusMapperTest {

    private static final Map<PositionStatus, ClientStatus> EXPECTED = Map.ofEntries(
            Map.entry(PositionStatus.PLANNED, ClientStatus.NOT_STARTED),
            Map.entry(PositionStatus.PROPOSED, ClientStatus.NOT_STARTED),
            Map.entry(PositionStatus.ACCEPTED, ClientStatus.ACCEPTED),
            Map.entry(PositionStatus.IN_PROFORMA, ClientStatus.IN_PROGRESS),
            Map.entry(PositionStatus.CLIENT_PAID, ClientStatus.IN_PROGRESS),
            Map.entry(PositionStatus.WITH_BUYER, ClientStatus.IN_PROGRESS),
            Map.entry(PositionStatus.ORDERED, ClientStatus.IN_PROGRESS),
            Map.entry(PositionStatus.SUPPLIER_PAID, ClientStatus.IN_PROGRESS),
            Map.entry(PositionStatus.IN_DELIVERY, ClientStatus.IN_DELIVERY),
            Map.entry(PositionStatus.PARTIALLY_RECEIVED, ClientStatus.IN_DELIVERY),
            Map.entry(PositionStatus.RECEIVED, ClientStatus.RECEIVED),
            Map.entry(PositionStatus.CANCELLED, ClientStatus.CANCELLED));

    @Test
    void everyStatusMapsPerRequirement() {
        for (PositionStatus s : PositionStatus.values()) {
            assertThat(ClientStatusMapper.map(s, SourcingPath.AGREED, false)).as(s.name()).isEqualTo(EXPECTED.get(s));
        }
    }

    @Test
    void openRoundIsAwaitingDecision() {
        for (PositionStatus s : new PositionStatus[] {PositionStatus.PLANNED, PositionStatus.PROPOSED, PositionStatus.ACCEPTED}) {
            assertThat(ClientStatusMapper.map(s, SourcingPath.AGREED, true)).isEqualTo(ClientStatus.AWAITING_DECISION);
        }
        assertThat(ClientStatusMapper.map(PositionStatus.PLANNED, SourcingPath.PACKAGE, true))
                .isEqualTo(ClientStatus.AWAITING_DECISION);
    }

    @Test
    void clientOwnPathWins() {
        for (PositionStatus s : PositionStatus.values()) {
            assertThat(ClientStatusMapper.map(s, SourcingPath.CLIENT_OWN, false)).isEqualTo(ClientStatus.CLIENT_OWN);
        }
    }
}
