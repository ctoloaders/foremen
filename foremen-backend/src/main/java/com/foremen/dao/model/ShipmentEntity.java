package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** FOR-11-04 (R2.1): a shipment of purchased goods to a project ({@code shipments}). */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_order_id")
    private ExternalOrderEntity externalOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 20)
    private DeliveryMethod deliveryMethod;

    @Column(name = "carrier_code", length = 30)
    private String carrierCode;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    @Column(name = "tracking_url", length = 1000)
    private String trackingUrl;

    @Column(name = "shop_order_link", length = 1000)
    private String shopOrderLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status = ShipmentStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_source", nullable = false, length = 10)
    private ShipmentSource statusSource = ShipmentSource.MANUAL;

    @Column(name = "eta_date")
    private LocalDate etaDate;

    @Column(name = "eta_time_from")
    private LocalTime etaTimeFrom;

    @Column(name = "eta_time_to")
    private LocalTime etaTimeTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_source", nullable = false, length = 10)
    private EtaSource etaSource = EtaSource.TRACKING;

    @Column(name = "driver_name", length = 200)
    private String driverName;

    @Column(name = "driver_phone", length = 40)
    private String driverPhone;

    @Column(length = 2000)
    private String note;

    @Column(name = "shipped_at", nullable = false)
    private OffsetDateTime shippedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "last_event_at")
    private OffsetDateTime lastEventAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "next_check_at")
    private OffsetDateTime nextCheckAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /** One-shot guard: the ETA date for which the downtime-risk notification was already sent. */
    @Column(name = "downtime_notified_for")
    private LocalDate downtimeNotifiedFor;

    /** FOR-11-05 (R8.1): the receipt event that opened this system re-delivery shipment; {@code null} otherwise. */
    @Column(name = "origin_receipt_id")
    private Long originReceiptId;
}
