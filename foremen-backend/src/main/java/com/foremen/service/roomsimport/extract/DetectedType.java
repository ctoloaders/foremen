package com.foremen.service.roomsimport.extract;

/** Result of content sniffing: the kind plus a canonical MIME type (null for text/unsupported). */
public record DetectedType(InputKind kind, String mimeType) {}
