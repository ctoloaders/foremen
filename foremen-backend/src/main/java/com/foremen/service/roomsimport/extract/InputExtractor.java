package com.foremen.service.roomsimport.extract;

/** Extracts text and/or page images from one uploaded input. Implementations may throw on bad data. */
public interface InputExtractor {

    boolean supports(InputKind kind);

    ExtractedInput extract(String fileName, byte[] content, DetectedType type) throws Exception;
}
