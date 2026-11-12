package com.foremen.service.dashboard;

/**
 * Access-independent structural descriptor of a library widget used by the client to draw a sample
 * thumbnail (FOR-10 R22.1). Carries no field names, labels, filters, dataset code or config values:
 * the only strings are the {@code visualization} and the default {@code style} catalog codes.
 */
public record ThumbnailShape(String visualization, String style, int columns, int dimensions, int measures,
                             boolean temporal, boolean stacked, boolean compareToPrevious) {
}
