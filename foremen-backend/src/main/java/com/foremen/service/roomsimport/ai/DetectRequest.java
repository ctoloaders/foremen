package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.extract.PageImage;
import java.util.List;

/**
 * Input of the detection call.
 *
 * @param userPrompt the free-text prompt typed by the user (may be blank)
 * @param texts      extracted text per input file
 * @param images     page images of all inputs
 * @param roomTypes  the room type catalogue offered to the model (id, code, RU, PL names)
 */
public record DetectRequest(String userPrompt, List<TextSource> texts, List<PageImage> images,
                            List<RoomTypeRef> roomTypes) {

    /** Extracted text of one file. */
    public record TextSource(String fileName, String text) {}

    /** Catalogue entry shown to the model. */
    public record RoomTypeRef(Long id, String code, String nameRu, String namePl) {}

    public DetectRequest {
        texts = texts == null ? List.of() : List.copyOf(texts);
        images = images == null ? List.of() : List.copyOf(images);
        roomTypes = roomTypes == null ? List.of() : List.copyOf(roomTypes);
    }
}
