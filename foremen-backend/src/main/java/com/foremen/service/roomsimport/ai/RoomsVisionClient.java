package com.foremen.service.roomsimport.ai;

/**
 * FOR-05-02 (design §B4, Requirements 3.1, 5.1, 13.1) — the vision/LLM seam of the rooms
 * auto-import pipeline. Two structured-output calls: detect the rooms in the inputs, then measure
 * one room. Implementations: {@link GeminiRoomsVisionClient} (enabled), {@link DisabledRoomsVisionClient}
 * (feature off) and {@link StubRoomsVisionClient} (deterministic canned answers for tests/Docker).
 *
 * <p>Implementations throw {@link RoomsVisionException} and never return partially valid data.
 */
public interface RoomsVisionClient {

    /** {@code false} only for the disabled wiring. */
    boolean isEnabled();

    /** Stage 2: find the rooms in the prompt, extracted text and page images. */
    DetectResponse detectRooms(DetectRequest request);

    /** Stage 4: extract the geometry/scalars of one detected room. */
    MeasureResponse measureRoom(MeasureRequest request);
}
