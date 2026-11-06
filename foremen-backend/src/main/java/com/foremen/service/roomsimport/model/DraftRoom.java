package com.foremen.service.roomsimport.model;

import com.foremen.controller.model.RoomCreateRequest;
import java.util.List;
import java.util.Map;

/** One recognised room: a ready-to-save {@link RoomCreateRequest} plus diagnostics. */
public record DraftRoom(
        String tempId,
        double confidence,
        SourceRef source,
        RoomCreateRequest room,
        CalculatedMetrics calculated,
        Map<String, Object> stated,
        List<DraftMedia> media,
        List<Warning> warnings) {}
