package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-11-05 (R2.2): a photo attached to a receipt event ({@code goods_receipt_media}, changeset 244). */
@Entity
@Table(name = "goods_receipt_media")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceiptMediaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private GoodsReceiptEntity receipt;

    /** Object storage key; never exposed through the API. */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
}
