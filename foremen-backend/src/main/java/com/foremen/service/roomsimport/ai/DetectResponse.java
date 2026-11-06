package com.foremen.service.roomsimport.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structured output of the detection call (design §B4 stage 2).
 *
 * @param statedRoomCount the number of rooms the inputs claim, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DetectResponse(List<DetectedRoom> rooms, Integer statedRoomCount, String notes) {

    /**
     * @param roomTypeId id from the offered catalogue, or null
     * @param bbox       {@code [ymin, xmin, ymax, xmax]} normalised to 0..1000, or null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetectedRoom(String tempId, String label, String typeHint, Long roomTypeId,
                               SourceRef sourceRef, List<Double> bbox, Double confidence) {}

    /** File (and page) a room was found on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRef(String fileName, Integer page) {}

    public DetectResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }
}
