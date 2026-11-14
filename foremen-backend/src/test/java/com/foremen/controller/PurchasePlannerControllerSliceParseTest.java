package com.foremen.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;

/** FOR-11-01 task 4.2: slice parameter validation of {@link PurchasePlannerController}. */
class PurchasePlannerControllerSliceParseTest {

    @Test
    void missingOrBlankSliceMeansAll() {
        assertEquals(Slice.ALL, PurchasePlannerController.parseSlice(null));
        assertEquals(Slice.ALL, PurchasePlannerController.parseSlice("  "));
    }

    @Test
    void knownSlicesParseCaseInsensitively() {
        assertEquals(Slice.LATE, PurchasePlannerController.parseSlice("late"));
        assertEquals(Slice.NOT_PAID, PurchasePlannerController.parseSlice("NOT_PAID"));
    }

    @Test
    void unknownSliceIsBadRequestWithLocalizedKey() {
        ForemenApiException e = assertThrows(ForemenApiException.class,
                () -> PurchasePlannerController.parseSlice("bogus"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("error.purchasePlanner.slice.invalid", e.getMessageCode());
    }
}
