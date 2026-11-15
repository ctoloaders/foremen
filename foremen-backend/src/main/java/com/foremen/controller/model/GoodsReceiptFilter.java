package com.foremen.controller.model;

import java.time.OffsetDateTime;

import com.foremen.dao.model.ReceiptKind;

/** FOR-11-05 (R9.1): list filters; every field is optional. {@code includeVoided} defaults to false. */
public record GoodsReceiptFilter(
        Long projectId,
        Long positionId,
        Long shipmentId,
        ReceiptKind kind,
        Boolean includeVoided,
        OffsetDateTime from,
        OffsetDateTime to) {
}
