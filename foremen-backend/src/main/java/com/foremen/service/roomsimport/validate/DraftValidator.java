package com.foremen.service.roomsimport.validate;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.service.RoomCalculationService;
import com.foremen.service.model.RoomMetrics;
import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.Warning;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, non-blocking validation of draft rooms (FOR-05-02, Requirement 7). Every outcome is
 * a warning: the validator never alters, removes or rejects a room and never throws for well-typed
 * input (a failing rule is skipped).
 */
@Component
public class DraftValidator {

    private final RoomCalculationService calculationService;
    private final RoomsImportProperties.Validation thresholds;
    private final List<ValidationRule> rules = List.of(
            new GeometryValidityRule(),
            new AreaConsistencyRule(),
            new OpeningsRule(),
            new CeilingHeightRule(),
            new InternalCornersRule(),
            new DimensionRangeRule(),
            new ConfidenceRule());

    @Autowired
    public DraftValidator(RoomCalculationService calculationService, RoomsImportProperties properties) {
        this(calculationService, properties.validation());
    }

    public DraftValidator(RoomCalculationService calculationService, RoomsImportProperties.Validation thresholds) {
        this.calculationService = calculationService;
        this.thresholds = thresholds;
    }

    /**
     * The authoritative {@code calculated} block (Requirement 7.4): with geometry, the unmodified
     * {@link RoomCalculationService} output; without geometry, a mirror of the scalar values.
     */
    public CalculatedMetrics calculate(RoomCreateRequest room) {
        if (room.geometry() != null) {
            RoomMetrics m = calculationService.calculate(room.geometry(), room.ceilingHeight());
            return new CalculatedMetrics(m.floorArea(), m.wallArea(), m.perimeter(), m.doorArea(),
                    m.windowArea(), m.doorCount(), m.windowCount());
        }
        return new CalculatedMetrics(room.floorArea(), room.wallArea(), room.perimeter(), room.doorArea(),
                room.windowArea(), room.doorCount(), room.windowCount());
    }

    /** Room-level warnings (rules of Requirement 7.1 plus {@code LOW_CONFIDENCE}); the draft is untouched. */
    public List<Warning> validate(DraftRoom draft) {
        List<Warning> out = new ArrayList<>();
        if (draft == null) {
            return out;
        }
        ValidationContext ctx = ValidationContext.of(draft, thresholds);
        for (ValidationRule rule : rules) {
            try {
                out.addAll(rule.apply(ctx));
            } catch (RuntimeException ignored) {
                // Validation is advisory: a failing rule must never fail the job.
            }
        }
        return out;
    }

    /** Room-level warnings plus {@code POSSIBLE_DUPLICATE_OF_EXISTING} against the project's existing rooms. */
    public List<Warning> validate(DraftRoom draft, List<ExistingRoomRef> existing) {
        List<Warning> out = validate(draft);
        try {
            Warning dup = DuplicateOfExistingRule.forRoom(draft, existing);
            if (dup != null) {
                out.add(dup);
            }
        } catch (RuntimeException ignored) {
            // advisory only
        }
        return out;
    }

    /** A copy of the draft with the new warnings appended after its current ones. */
    public DraftRoom validated(DraftRoom draft, List<ExistingRoomRef> existing) {
        List<Warning> all = new ArrayList<>();
        if (draft.warnings() != null) {
            all.addAll(draft.warnings());
        }
        all.addAll(validate(draft, existing));
        return new DraftRoom(draft.tempId(), draft.confidence(), draft.source(), draft.room(),
                draft.calculated(), draft.stated(), draft.media(), List.copyOf(all));
    }
}
