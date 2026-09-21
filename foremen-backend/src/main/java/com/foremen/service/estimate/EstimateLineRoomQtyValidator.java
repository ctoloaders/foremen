package com.foremen.service.estimate;

import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Cross-project + non-negative validation for an {@link EstimateLineRoomQtyEntity} write
 * (FOR-05-03, Requirement 3.2, 3.6; design §6.5 {@code validateRoomQty}).
 *
 * <p>A pure, stateless validator invoked from {@code EstimateLineRoomQtyService}'s
 * create/update hooks before any persistence, mirroring the {@code EffectivePriceResolver}
 * convention of a stateless Spring {@code @Component} that holds no state and performs no I/O
 * beyond reading the already-loaded entity graph passed to it.
 *
 * <p>Two independent checks, both against the room quantity row about to be
 * created/updated:
 * <ol>
 *   <li><b>Cross-project rejection (R3.6).</b> The referenced {@code room}'s owning project
 *       must equal the owning line's estimate project ({@code line.estimate.project.id}).
 *       A mismatch is rejected with {@code 400 error.estimate.room.cross.project} — a room
 *       cannot be associated with a line whose estimate belongs to a different project.</li>
 *   <li><b>Non-negative quantity (R3.2).</b> {@code quantity} must be {@code >= 0}; a negative
 *       value is rejected with {@code 400 error.estimate.qty.negative}.</li>
 * </ol>
 *
 * <p>Both checks throw before any write, so the enclosing transaction rolls back with nothing
 * persisted (matching the repo convention in {@code FinishingMaterialService}/
 * {@code WorkMaterialConsumptionService}).
 */
@Component
public class EstimateLineRoomQtyValidator {

    private static final String CROSS_PROJECT_MESSAGE = "error.estimate.room.cross.project";
    private static final String NEGATIVE_QTY_MESSAGE = "error.estimate.qty.negative";

    /**
     * Validates {@code roomQty} against the cross-project rule (R3.6) and the non-negative
     * quantity rule (R3.2), per design §6.5.
     *
     * @param roomQty the room quantity row about to be created/updated, with {@code line}
     *                (and its {@code estimate.project}) and {@code room} (and its
     *                {@code project}) already resolved/loaded
     * @throws ForemenApiException 400 {@code error.estimate.room.cross.project} when the
     *                              room's project differs from the line's estimate project
     * @throws ForemenApiException 400 {@code error.estimate.qty.negative} when
     *                              {@code quantity < 0}
     */
    public void validateRoomQty(EstimateLineRoomQtyEntity roomQty) {
        Long lineProjectId = roomQty.getLine().getEstimate().getProject().getId();
        Long roomProjectId = roomQty.getRoom().getProject().getId();

        if (!lineProjectId.equals(roomProjectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, CROSS_PROJECT_MESSAGE);
        }

        BigDecimal quantity = roomQty.getQuantity();
        if (quantity != null && quantity.signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NEGATIVE_QTY_MESSAGE);
        }
    }
}
