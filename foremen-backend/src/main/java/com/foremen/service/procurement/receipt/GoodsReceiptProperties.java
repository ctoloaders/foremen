package com.foremen.service.procurement.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * FOR-11-05: {@code foremen.goods-receipt.*}. Fallbacks equal the shipped defaults in application.yml
 * (media 8 per request / 10 per event / 5 MB per file, author void window 24 h).
 */
@ConfigurationProperties(prefix = "foremen.goods-receipt")
public record GoodsReceiptProperties(
        @DefaultValue Media media,
        @DefaultValue("PT24H") Duration voidWindow) {

    public GoodsReceiptProperties {
        if (media == null) {
            media = new Media(8, 10, DataSize.ofMegabytes(5));
        }
        if (voidWindow == null) {
            voidWindow = Duration.ofHours(24);
        }
    }

    /** Media limits for receipt events. */
    public record Media(
            @DefaultValue("8") int maxPerRequest,
            @DefaultValue("10") int maxPerEvent,
            @DefaultValue("5MB") DataSize maxFileSize) {
    }

    /** Pure-rules view of the configured media limits. */
    public GoodsReceiptRules.MediaLimits mediaLimits() {
        return new GoodsReceiptRules.MediaLimits(
                media.maxPerRequest(), media.maxPerEvent(), media.maxFileSize().toBytes());
    }
}
