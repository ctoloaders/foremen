package com.foremen.service.roomsimport;

/**
 * One entry of the job's {@code inputs} manifest (design B3).
 *
 * @param fileName    sanitised client file name (metadata only)
 * @param contentType declared content type
 * @param sizeBytes   size of the stored object
 * @param objectKey   server-generated key under {@code rooms-import/{jobId}/in/}
 * @param kind        coarse kind label (informational)
 */
public record StoredInput(String fileName, String contentType, long sizeBytes, String objectKey, String kind) {
}
