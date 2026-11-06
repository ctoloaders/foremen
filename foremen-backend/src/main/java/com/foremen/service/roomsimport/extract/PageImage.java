package com.foremen.service.roomsimport.extract;

/**
 * An image handed to the vision model. {@code croppable=false} means the bytes could not be
 * decoded locally (HEIC/HEIF/WEBP without a reader) and are forwarded as is.
 *
 * @param page 1-based page number for PDF pages, otherwise null
 */
public record PageImage(byte[] bytes, String mimeType, int width, int height, boolean croppable,
                        String fileName, Integer page) {}
