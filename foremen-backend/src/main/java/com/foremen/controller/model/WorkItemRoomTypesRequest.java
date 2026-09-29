package com.foremen.controller.model;

import java.util.List;

/**
 * Request body for {@code PUT /api/work-items/{id}/room-types} (FOR-05-05, R10.5): the full desired
 * set of Room_Type_Attachment ids for a work item.
 *
 * <p>The list is a REPLACE, not a delta — the work item's {@code roomTypes} collection is set to
 * exactly the referenced room types. A {@code null} or empty list clears the attachment, meaning the
 * work attaches to ALL rooms on apply (R10.3). Each referenced id must resolve to an existing
 * {@code RoomType} or the request is rejected with {@code 404 error.entity.not.found}.
 *
 * @param roomTypeIds the desired attached {@code RoomType} ids (may be {@code null}/empty to clear)
 */
public record WorkItemRoomTypesRequest(List<Long> roomTypeIds) {
}
