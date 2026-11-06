package com.foremen.dao.model;

/**
 * FOR-05-02 (Requirement 10.1): the kind of a {@link RoomMediaEntity}.
 *
 * <p>{@link #SOURCE_CROP} is an image cropped from the AI-import source plan; {@link #UPLOAD} is a
 * file uploaded manually onto the room. Stored as a string.
 */
public enum RoomMediaKind {
    SOURCE_CROP,
    UPLOAD
}
