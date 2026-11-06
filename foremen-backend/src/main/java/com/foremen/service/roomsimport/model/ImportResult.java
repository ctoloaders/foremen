package com.foremen.service.roomsimport.model;

import java.util.List;

/** The job {@code result}: draft rooms, project-level warnings, input summaries, model info. */
public record ImportResult(
        List<DraftRoom> rooms,
        List<Warning> warnings,
        List<InputInfo> inputs,
        ModelInfo modelInfo) {}
