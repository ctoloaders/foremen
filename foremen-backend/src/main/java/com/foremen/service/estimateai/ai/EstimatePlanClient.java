package com.foremen.service.estimateai.ai;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * FOR-05-04 (design B5) — the model boundary of the PLANNING_WORKS stage. Implementations: the Gemini
 * client (enabled and not stub), the deterministic stub and the disabled fallback.
 */
public interface EstimatePlanClient {

    boolean isEnabled();

    /** Returns the raw plan JSON ({@code works[]}, {@code assumptions[]}, {@code questions[]}, {@code unpriced[]}). */
    PlanResponse plan(PlanRequest request);

    /**
     * @param systemInstruction the versioned system prompt
     * @param userInstruction   the user's prompt, passed as a separate delimited block
     * @param catalogText       the catalog context (no prices)
     * @param roomsText         the saved rooms with dimensions
     * @param roomIds           ids of the saved rooms (used by the deterministic stub)
     * @param workCodes         codes of the works in {@code catalogText}, catalog order
     * @param reaskText         when not blank, a follow-up asking only for missing per-cell rationales
     *                          (Requirement 21.6); {@code null} for a normal planning call
     * @param attachments       the job's input files (design images, PDF) re-attached to the call, may be empty
     */
    record PlanRequest(String systemInstruction, String userInstruction, String catalogText,
                       String roomsText, List<Long> roomIds, List<String> workCodes, String reaskText,
                       List<Attachment> attachments) {

        public PlanRequest {
            roomIds = roomIds == null ? List.of() : List.copyOf(roomIds);
            workCodes = workCodes == null ? List.of() : List.copyOf(workCodes);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public PlanRequest(String systemInstruction, String userInstruction, String catalogText,
                           String roomsText, List<Long> roomIds, List<String> workCodes, String reaskText) {
            this(systemInstruction, userInstruction, catalogText, roomsText, roomIds, workCodes, reaskText, null);
        }

        public PlanRequest(String systemInstruction, String userInstruction, String catalogText,
                           String roomsText, List<Long> roomIds, List<String> workCodes) {
            this(systemInstruction, userInstruction, catalogText, roomsText, roomIds, workCodes, null, null);
        }
    }

    /** An input file of the job (design image or PDF) re-attached to the planning call. */
    record Attachment(String mimeType, byte[] data) {
    }

    /** @param plan raw JSON answer of the model, validated later by the plan resolver */
    record PlanResponse(JsonNode plan) {
    }
}
