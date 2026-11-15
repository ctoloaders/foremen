package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** FOR-11-03 (R11.3): a supplier document attached to an external order; unique per (order, document). */
@Entity
@Table(name = "external_order_documents")
@Getter
@Setter
@NoArgsConstructor
public class ExternalOrderDocumentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_order_id", nullable = false)
    private ExternalOrderEntity externalOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procurement_document_id", nullable = false)
    private ProcurementDocumentEntity procurementDocument;

    /** Portion of the document total attributed to this order; null means the whole document. */
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;
}
