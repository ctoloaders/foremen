package com.foremen.service.estimate;

import java.util.List;

import com.foremen.dao.model.EstimateEntity;

/**
 * FOR-05-04 (design B11): the hook {@link EstimateScratchService} calls, inside the scratch transaction
 * and after the replay, to set the cell AI meta on the scratch cells. Implemented by the cell meta
 * overlay of task 3.11; the scratch service never calls it with an empty list.
 */
public interface ScratchMetaOverlay {

    /** Merges the model-side {@code inputs} with the engine facts onto the scratch estimate's cells. */
    void apply(EstimateEntity estimate, Long projectId, List<CellMetaInput> inputs);
}
