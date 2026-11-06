package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.extract.PageImage;

/**
 * Input of the per-room measuring call.
 *
 * @param tempId     the draft room id from detection
 * @param label      room label
 * @param typeHint   room type hint
 * @param image      the room crop, or the full page when no crop is available; may be null
 * @param text       the relevant extracted text; may be null
 * @param userPrompt the user's prompt (unit/scale hints)
 * @param sourceName file name the room was detected in
 */
public record MeasureRequest(String tempId, String label, String typeHint, PageImage image,
                             String text, String userPrompt, String sourceName) {}
