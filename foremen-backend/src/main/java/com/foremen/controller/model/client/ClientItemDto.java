package com.foremen.controller.model.client;

/** FOR-11-02: material shown to the client. */
public record ClientItemDto(Long materialId, String model, String sku, String producerName,
                            String link, String photo) {
}
