package com.foremen.config.bot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link BotProperties} binding for the loaders-bot integration (FOR-08).
 */
@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class BotConfig {
}
