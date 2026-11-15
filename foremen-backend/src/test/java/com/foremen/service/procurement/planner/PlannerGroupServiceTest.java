package com.foremen.service.procurement.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.planner.PlannerGroupService.GroupBy;
import com.foremen.service.procurement.planner.PlannerGroupService.Line;
import com.foremen.service.procurement.planner.PlannerGroupService.Totals;

/** FOR-11-01 task 4.3: groupBy validation and group totals aggregation (R3.4, R4.5). */
class PlannerGroupServiceTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void groupByDefaultsToRequestAndAcceptsNone() {
        assertEquals(GroupBy.REQUEST, PlannerGroupService.parseGroupBy(null));
        assertEquals(GroupBy.REQUEST, PlannerGroupService.parseGroupBy(" "));
        assertEquals(GroupBy.NONE, PlannerGroupService.parseGroupBy("none"));
    }

    @Test
    void newModesAreAcceptedAndUnknownGroupByIsRejected() {
        assertEquals(GroupBy.PROFORMA, PlannerGroupService.parseGroupBy("proforma"));
        assertEquals(GroupBy.EXTERNAL_ORDER, PlannerGroupService.parseGroupBy("EXTERNAL_ORDER"));
        for (String v : List.of("bogus", "REQUESTS")) {
            ForemenApiException e = assertThrows(ForemenApiException.class,
                    () -> PlannerGroupService.parseGroupBy(v));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
            assertEquals("error.purchasePlanner.groupBy.invalid", e.getMessageCode());
        }
    }

    @Test
    void totalsSumRoundedLinesAndMargin() {
        // line1: sale 10.00 x 3 = 30.00 (vat 6.90); cost 6.00 x 3 = 18.00, delivery 2.00
        // line2: sale 1.005 x 3 = 3.015 -> 3.02 (vat 0.69); no purchase price -> margin unavailable
        Totals t = PlannerGroupService.aggregate(List.of(
                new Line(d("3"), d("10.00"), d("23"), null, d("6.00"), d("2.00")),
                new Line(d("3"), d("1.005"), d("23"), null, null, null)));
        assertEquals(d("33.02"), t.saleNet());
        assertEquals(d("40.61"), t.saleGross());
        assertEquals(d("18.00"), t.costNet());
        assertEquals(d("2.00"), t.deliveryCost());
        assertEquals(d("10.00"), t.marginAmount());
        assertEquals(0, d("0.333333").compareTo(t.marginPct()));
        assertTrue(t.marginIncomplete());
    }

    @Test
    void emptyGroupHasZeroSumsAndNoMargin() {
        Totals t = PlannerGroupService.emptyTotals();
        assertEquals(0, BigDecimal.ZERO.compareTo(t.saleNet()));
        assertNull(t.marginAmount());
        assertNull(t.marginPct());
        assertFalse(t.marginIncomplete());
    }
}
