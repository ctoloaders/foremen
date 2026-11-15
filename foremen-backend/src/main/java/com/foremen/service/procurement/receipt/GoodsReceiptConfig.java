package com.foremen.service.procurement.receipt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** FOR-11-05: binds {@link GoodsReceiptProperties} at startup. */
@Configuration
@EnableConfigurationProperties(GoodsReceiptProperties.class)
public class GoodsReceiptConfig {
}
