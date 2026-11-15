package com.foremen.controller.model;

/** FOR-11-05 (R7.5): receipt photo metadata. The storage key never leaves the backend. */
public record GoodsReceiptMediaDto(Long id, String fileName, Long sizeBytes, String contentType) {
}
