package com.foremen.controller.model;

import java.util.List;

import com.foremen.controller.AdminController;

import jakarta.validation.Valid;

/**
 * FOR-05-05 additional task 3 — the Rooms-tab atomic save: the whole pending change set of the rooms
 * matrix in one request ({@code POST /api/rooms/project/{projectId}/save}). Each group mirrors the
 * existing bulk contracts ({@code POST /api/rooms/bulk}, {@code PUT /api/rooms/bulk},
 * {@code DELETE /api/rooms/{id}}) so the client payload is unchanged; any group may be empty.
 *
 * @param createList  new rooms
 * @param updateItems edited existing rooms as {@code {id, data}} pairs
 * @param deleteIds   rooms to delete
 */
public record RoomsSaveRequest(
        @Valid List<RoomCreateRequest> createList,
        @Valid List<AdminController.BulkUpdateItem<Long, RoomUpdateRequest>> updateItems,
        List<Long> deleteIds) {
}
