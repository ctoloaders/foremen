package com.foremen.controller.model;

import java.util.List;

/**
 * Response for the Work Catalog Room_Type_Attachment endpoints (FOR-05-05, R10.5): the ids of the
 * room types currently attached to a work item.
 *
 * <p>Returned by both {@code GET /api/work-items/{id}/room-types} and
 * {@code PUT /api/work-items/{id}/room-types}. An empty list means the work has no
 * Room_Type_Attachment and therefore attaches to ALL rooms on apply (R10.3).
 *
 * @param roomTypeIds the attached {@code RoomType} ids (never {@code null}; possibly empty)
 */
public record WorkItemRoomTypesResponse(List<Long> roomTypeIds) {
}
